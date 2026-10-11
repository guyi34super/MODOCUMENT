package com.modocument.application.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.dto.ChatEditPatchPromptInput;
import com.modocument.application.dto.ChatEditPatchResponse;
import com.modocument.application.dto.ChatEditPromptInput;
import com.modocument.application.dto.RowPatch;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.PromptStrategy;
import com.modocument.infrastructure.prompt.PromptFactory;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.settings.SettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Chat-based table editing. When the job has validation errors, only the cell,
 * row, column, or error-row set implied by the instruction (plus optional UI
 * hints) is sent to the LLM; the model returns a sparse patch merged back by
 * row index. Without validation errors it falls back to sending the whole
 * table. Validation and export checkpoints are cleared because the data changed.
 */
@Service
public class ChatEditTableUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChatEditTableUseCase.class);

    /** Keeps the prompt within a sane size; larger tables should be edited via the API. */
    public static final int MAX_ROWS = 500;

    private final IngestionJobRepository jobs;
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final PiiTokenizer tokenizer;
    private final com.modocument.application.port.CurrentUserProvider currentUser;
    private final ChatEditProperties properties;
    /** Null = the yml ratio only (unit-test wiring). */
    private final SettingsReader settings;
    /** Null = no audit (unit-test wiring). */
    private final AuditRecorder audit;

    public ChatEditTableUseCase(IngestionJobRepository jobs,
                                List<PromptStrategy> promptStrategies,
                                AiFilesService aiFilesService,
                                PiiTokenizer tokenizer,
                                com.modocument.application.port.CurrentUserProvider currentUser,
                                ChatEditProperties properties) {
        this(jobs, promptStrategies, aiFilesService, tokenizer, currentUser, properties, null);
    }

    public ChatEditTableUseCase(IngestionJobRepository jobs,
                                List<PromptStrategy> promptStrategies,
                                AiFilesService aiFilesService,
                                PiiTokenizer tokenizer,
                                com.modocument.application.port.CurrentUserProvider currentUser,
                                ChatEditProperties properties,
                                SettingsReader settings) {
        this(jobs, promptStrategies, aiFilesService, tokenizer, currentUser, properties, settings, null);
    }

    @Autowired
    public ChatEditTableUseCase(IngestionJobRepository jobs,
                                List<PromptStrategy> promptStrategies,
                                AiFilesService aiFilesService,
                                PiiTokenizer tokenizer,
                                com.modocument.application.port.CurrentUserProvider currentUser,
                                ChatEditProperties properties,
                                SettingsReader settings,
                                AuditRecorder audit) {
        this.audit = audit;
        this.jobs = jobs;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.tokenizer = tokenizer;
        this.currentUser = currentUser;
        this.properties = properties;
        this.settings = settings;
    }

    public IngestionJob handle(int jobId, String instruction) {
        return handle(jobId, instruction, null, null);
    }

    public IngestionJob handle(int jobId, String instruction, Integer hintRow, String hintColumn) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required.");
        }
        IngestionJob job = jobs.getByIdAndOwner(jobId, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("Ingestion job " + jobId + " not found."));
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Job " + jobId + " is cancelled.");
        }

        boolean editMapped = job.getMappedRowsJson() != null;
        String tableJson = editMapped ? job.getMappedRowsJson() : job.getParsedTableJson();
        if (tableJson == null) {
            throw new IllegalStateException("Job has no table yet; run parse first.");
        }

        TableData current = IngestionJobUseCase.fromJson(tableJson, TableData.class);
        List<RowValidationResult> validation =
                editMapped ? readValidationResults(job.getValidationResultJson()) : List.of();
        List<Integer> errorRows = errorRowIndices(validation, current.getRows().size());

        PiiTokenizer.Session session = tokenizer.newSession();
        int bankId = job.getBankId();
        TableData modified = errorRows.isEmpty()
                ? editFullTable(instruction, current, session, bankId)
                : editScopedPatch(instruction, hintRow, hintColumn, current, validation, errorRows,
                        session, bankId);

        String modifiedJson = IngestionJobUseCase.toJson(modified);
        if (editMapped) {
            job.setMappedRowsJson(modifiedJson);
            job.setCurrentStep(JobStep.MAPPED);
        } else {
            job.setParsedTableJson(modifiedJson);
            job.setCurrentStep(JobStep.PARSED);
        }
        job.setValidationResultJson(null);
        job.setExportInfoJson(null);
        IngestionJobUseCase.clampWizardStep(job);
        job.setStatus(JobStatus.COMPLETED);
        job.setErrorMessage(null);
        IngestionJob edited = jobs.save(job);
        if (audit != null) { // BUG-11: same row as PUT .../rows — counts only, never cell values
            List<Map<String, String>> after = modified.getRows() == null ? List.of() : modified.getRows();
            List<Map<String, String>> before = current.getRows() == null ? List.of() : current.getRows();
            audit.success(AuditActions.INGESTION_ROWS_EDITED, "IngestionJob", String.valueOf(jobId),
                    null, Map.of("source", "CHAT_EDIT", "rows", after.size(),
                            "changedRows", IngestionJobUseCase.changedRows(before, after)));
        }
        return edited;
    }

    private TableData editFullTable(String instruction, TableData current,
                                    PiiTokenizer.Session session, int bankId) {
        if (current.getRows() != null && current.getRows().size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Table has more than " + MAX_ROWS + " rows; chat editing is limited to "
                            + MAX_ROWS + " rows.");
        }
        String tokenizedJson = IngestionJobUseCase.toJson(
                new TableData(current.getColumns(), session.tokenizeRows(current.getRows())));
        String prompt = strategy("ChatEdit")
                .buildPrompt(new ChatEditPromptInput(instruction, tokenizedJson));
        TableData modified = aiFilesService.sendForObject(
                PromptEnvelope.builder(AiPurpose.CHAT_EDIT)
                        .bank(bankId)
                        .part(prompt, bankId)
                        .model(properties.getModelId())
                        .build(),
                new ParameterizedTypeReference<TableData>() {
                });
        if (modified == null || modified.isEmpty()) {
            throw new IllegalStateException("The model returned an empty table; nothing was changed.");
        }
        return applyFullTable(instruction, current, modified, session);
    }

    private TableData applyFullTable(String instruction, TableData current, TableData modified,
                                     PiiTokenizer.Session session) {
        List<Map<String, String>> incoming = modified.getRows();
        if (incoming.size() != current.getRows().size()) {
            throw new IllegalStateException("The model returned " + incoming.size()
                    + " rows but the table has " + current.getRows().size()
                    + "; nothing was changed.");
        }
        List<Map<String, String>> sanitized = incoming.stream()
                .map(row -> row == null ? Map.<String, String>of() : row)
                .toList();
        List<Map<String, String>> restored = session.detokenizeRows(sanitized);

        List<Map<String, String>> rows = new ArrayList<>(restored.size());
        long changed = 0;
        for (int i = 0; i < restored.size(); i++) {
            Map<String, String> answer = restored.get(i);
            Map<String, String> row = new LinkedHashMap<>(current.getRows().get(i));
            for (String column : current.getColumns()) {
                if (answer.containsKey(column)) {
                    String value = answer.get(column) == null ? "" : answer.get(column);
                    String previous = current.getRows().get(i).getOrDefault(column, "");
                    if (!value.equals(previous)) {
                        changed++;
                    }
                    row.put(column, value);
                }
            }
            rows.add(row);
        }

        // Guardrail: a targeted instruction that rewrites most of the table is
        // almost always the model going rogue. Bulk phrasing ("all"/"every")
        // opts out, since those legitimately touch everything.
        long total = (long) current.getRows().size() * current.getColumns().size();
        if (!ChatEditScopeResolver.isBulkInstruction(instruction)
                && total > 0
                && (double) changed / total > maxChangeRatio()) {
            throw new IllegalStateException("The assistant tried to change " + changed + " of " + total
                    + " cells, which looks broader than the instruction asked for; nothing was changed. "
                    + "Target specific rows or columns, or say \"all\"/\"every\" for a deliberate bulk change.");
        }
        return new TableData(current.getColumns(), rows);
    }

    private TableData editScopedPatch(String instruction, Integer hintRow, String hintColumn,
                                      TableData current, List<RowValidationResult> validation,
                                      List<Integer> errorRows, PiiTokenizer.Session session,
                                      int bankId) {
        ChatEditScopeResolution scope = ChatEditScopeResolver.resolve(
                instruction, hintRow, hintColumn, current.getColumns(), validation);
        scope = normalizeScope(scope, current, validation, errorRows, hintRow, hintColumn);

        int payloadRows = countPayloadRows(scope, validation);
        if (payloadRows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Table has more than " + MAX_ROWS + " rows needing fixes; chat editing is limited to "
                            + MAX_ROWS + " rows.");
        }

        String columnsJson = IngestionJobUseCase.toJson(current.getColumns());
        String payloadJson = buildPayloadJson(scope, current, validation, session);
        String prompt = strategy("ChatEditPatch").buildPrompt(new ChatEditPatchPromptInput(
                scope.scope(), instruction, columnsJson, payloadJson));
        ChatEditPatchResponse patch = aiFilesService.sendForObject(
                PromptEnvelope.builder(AiPurpose.CHAT_EDIT)
                        .bank(bankId)
                        .part(prompt, bankId)
                        .model(properties.getModelId())
                        .build(),
                new ParameterizedTypeReference<ChatEditPatchResponse>() {
                });
        if (patch == null || patch.rows() == null || patch.rows().isEmpty()) {
            throw new IllegalStateException("The model returned no row changes; nothing was changed.");
        }
        List<RowPatch> detokenized = patch.rows().stream()
                .map(p -> new RowPatch(p.row(),
                        p.values() == null ? null : session.detokenizeRow(p.values())))
                .toList();
        Set<String> allowed = allowedCellsFor(scope, current, validation);
        return mergeScopedPatch(current, detokenized, allowed);
    }

    /**
     * Cells the model is allowed to change for a resolved scope. Anything the
     * model returns outside this set is dropped, so a "fix this cell" edit can
     * never quietly rewrite other rows or columns.
     */
    private static Set<String> allowedCellsFor(ChatEditScopeResolution scope, TableData current,
                                               List<RowValidationResult> validation) {
        Set<String> allowed = new java.util.HashSet<>();
        switch (scope.scope()) {
            case CELL -> allowed.add(cellKey(scope.row(), scope.column()));
            case ROW -> {
                for (String column : current.getColumns()) {
                    allowed.add(cellKey(scope.row(), column));
                }
            }
            case COLUMN -> {
                for (RowValidationResult r : validation) {
                    if (r.errorMessage() != null && scope.columns().contains(r.fieldName())) {
                        allowed.add(cellKey(r.row(), r.fieldName()));
                    }
                }
            }
            case ALL_ERRORS -> {
                for (int rowNum : errorRowIndices(validation, current.getRows().size())) {
                    for (String column : current.getColumns()) {
                        allowed.add(cellKey(rowNum, column));
                    }
                }
            }
        }
        return allowed;
    }

    private static String cellKey(int row, String column) {
        return row + "\u0001" + column;
    }

    /** Clamp resolved targets to rows/columns that actually have validation errors. */
    private static ChatEditScopeResolution normalizeScope(ChatEditScopeResolution scope,
                                                          TableData current,
                                                          List<RowValidationResult> validation,
                                                          List<Integer> errorRows,
                                                          Integer hintRow,
                                                          String hintColumn) {
        return switch (scope.scope()) {
            case CELL -> {
                int row = scope.row() != null ? scope.row() : hintRow != null ? hintRow : -1;
                String column = scope.column() != null ? scope.column() : hintColumn;
                if (row < 1 || row > current.getRows().size() || column == null || column.isBlank()) {
                    yield new ChatEditScopeResolution(ChatEditScope.ALL_ERRORS, null, null, List.of());
                }
                if (!hasCellError(validation, row, column)) {
                    yield new ChatEditScopeResolution(ChatEditScope.ROW, row, null, List.of());
                }
                yield new ChatEditScopeResolution(ChatEditScope.CELL, row, column, List.of());
            }
            case ROW -> {
                int row = scope.row() != null ? scope.row() : hintRow != null ? hintRow : -1;
                if (row < 1 || row > current.getRows().size() || !errorRows.contains(row)) {
                    yield new ChatEditScopeResolution(ChatEditScope.ALL_ERRORS, null, null, List.of());
                }
                yield new ChatEditScopeResolution(ChatEditScope.ROW, row, null, List.of());
            }
            case COLUMN -> {
                List<String> cols = scope.columns().isEmpty()
                        ? (hintColumn != null && !hintColumn.isBlank() ? List.of(hintColumn) : List.of())
                        : scope.columns();
                List<String> withErrors = cols.stream()
                        .filter(col -> validation.stream().anyMatch(r ->
                                r.errorMessage() != null && col.equals(r.fieldName())))
                        .toList();
                if (withErrors.isEmpty()) {
                    yield new ChatEditScopeResolution(ChatEditScope.ALL_ERRORS, null, null, List.of());
                }
                yield new ChatEditScopeResolution(
                        ChatEditScope.COLUMN, null, withErrors.get(0), withErrors);
            }
            case ALL_ERRORS -> scope;
        };
    }

    private static boolean hasCellError(List<RowValidationResult> validation, int row, String column) {
        return validation.stream()
                .anyMatch(r -> r.row() == row && column.equals(r.fieldName()) && r.errorMessage() != null);
    }

    private static int countPayloadRows(ChatEditScopeResolution scope, List<RowValidationResult> validation) {
        return switch (scope.scope()) {
            case CELL, ROW -> 1;
            case COLUMN -> Math.toIntExact(validation.stream()
                    .filter(r -> r.errorMessage() != null && scope.columns().contains(r.fieldName()))
                    .map(RowValidationResult::row)
                    .distinct()
                    .count());
            case ALL_ERRORS -> Math.toIntExact(validation.stream()
                    .filter(r -> r.errorMessage() != null)
                    .map(RowValidationResult::row)
                    .distinct()
                    .count());
        };
    }

    private static String buildPayloadJson(ChatEditScopeResolution scope,
                                           TableData current,
                                           List<RowValidationResult> validation,
                                           PiiTokenizer.Session session) {
        return switch (scope.scope()) {
            case CELL -> IngestionJobUseCase.toJson(buildCellPayload(
                    scope.row(), scope.column(), current, validation, session));
            case ROW -> IngestionJobUseCase.toJson(buildRowPayload(
                    scope.row(), current, validation, session));
            case COLUMN -> IngestionJobUseCase.toJson(buildColumnPayload(
                    scope.columns(), current, validation, session));
            case ALL_ERRORS -> IngestionJobUseCase.toJson(buildAllErrorRowsPayload(
                    errorRowIndices(validation, current.getRows().size()), current, validation, session));
        };
    }

    static Map<String, Object> buildCellPayload(int rowNum, String column, TableData current,
                                                List<RowValidationResult> validation,
                                                PiiTokenizer.Session session) {
        Map<String, String> rowValues = session.tokenizeRow(current.getRows().get(rowNum - 1));
        String error = validation.stream()
                .filter(r -> r.row() == rowNum && column.equals(r.fieldName()) && r.errorMessage() != null)
                .map(RowValidationResult::errorMessage)
                .findFirst()
                .orElse("");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("row", rowNum);
        payload.put("column", column);
        payload.put("value", rowValues.getOrDefault(column, ""));
        payload.put("error", error);
        return payload;
    }

    static Map<String, Object> buildRowPayload(int rowNum, TableData current,
                                               List<RowValidationResult> validation,
                                               PiiTokenizer.Session session) {
        Map<String, String> errors = errorsForRow(validation, rowNum);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("row", rowNum);
        payload.put("values", session.tokenizeRow(current.getRows().get(rowNum - 1)));
        payload.put("errors", errors);
        return payload;
    }

    static Map<String, Object> buildColumnPayload(List<String> columns, TableData current,
                                                  List<RowValidationResult> validation,
                                                  PiiTokenizer.Session session) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (RowValidationResult result : validation) {
            if (result.errorMessage() == null || !columns.contains(result.fieldName())) {
                continue;
            }
            int rowNum = result.row();
            Map<String, String> tokenized = session.tokenizeRow(current.getRows().get(rowNum - 1));
            cells.add(Map.of(
                    "row", rowNum,
                    "column", result.fieldName(),
                    "value", tokenized.getOrDefault(result.fieldName(), ""),
                    "error", result.errorMessage()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("columns", columns);
        payload.put("cells", cells);
        return payload;
    }

    static List<Map<String, Object>> buildAllErrorRowsPayload(List<Integer> errorRows,
                                                              TableData current,
                                                              List<RowValidationResult> validation,
                                                              PiiTokenizer.Session session) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (int rowNum : errorRows) {
            payload.add(Map.of(
                    "row", rowNum,
                    "values", session.tokenizeRow(current.getRows().get(rowNum - 1)),
                    "errors", errorsForRow(validation, rowNum)));
        }
        return payload;
    }

    private static Map<String, String> errorsForRow(List<RowValidationResult> validation, int rowNum) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (RowValidationResult result : validation) {
            if (result.row() == rowNum && result.errorMessage() != null) {
                errors.put(result.fieldName(), result.errorMessage());
            }
        }
        return errors;
    }

    /**
     * Applies model-proposed row patches to a copy of the table, but only for
     * cells inside {@code allowed} (the resolved scope). A row index outside
     * the table still means the model lost positional grounding, so that is a
     * hard reject. Cells outside the scope, or unknown columns, are dropped so
     * the assistant cannot quietly rewrite data the user did not target. If no
     * targeted cell actually changed, the edit is rejected so the UI can say so.
     */
    private static TableData mergeScopedPatch(TableData current, List<RowPatch> patches,
                                              Set<String> allowed) {
        List<Map<String, String>> rows = new ArrayList<>(current.getRows().size());
        for (Map<String, String> row : current.getRows()) {
            rows.add(new LinkedHashMap<>(row));
        }
        Set<String> columns = Set.copyOf(current.getColumns());

        int applied = 0;
        int dropped = 0;
        for (RowPatch patch : patches) {
            int index = patch.row() - 1;
            if (index < 0 || index >= rows.size()) {
                throw new IllegalStateException("The model returned row " + patch.row()
                        + ", which is outside the table (1-" + rows.size() + "); nothing was changed.");
            }
            if (patch.values() == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : patch.values().entrySet()) {
                String column = entry.getKey();
                if (!columns.contains(column)) {
                    log.warn("Chat edit patch for row {} contains unknown column '{}'; ignoring it.",
                            patch.row(), column);
                    continue;
                }
                if (!allowed.contains(cellKey(patch.row(), column))) {
                    dropped++;
                    log.debug("Chat edit patch for row {} column '{}' is outside the requested scope; "
                            + "dropping it.", patch.row(), column);
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                if (!value.equals(rows.get(index).getOrDefault(column, ""))) {
                    applied++;
                }
                rows.get(index).put(column, value);
            }
        }
        if (applied == 0) {
            throw new IllegalStateException(dropped > 0
                    ? "The assistant only changed cells outside what you asked to edit; nothing was changed."
                    : "The assistant did not change the targeted data; nothing was changed.");
        }
        return new TableData(current.getColumns(), rows);
    }

    private static List<RowValidationResult> readValidationResults(String validationResultJson) {
        if (validationResultJson == null || validationResultJson.isBlank()) {
            return List.of();
        }
        return IngestionJobUseCase.fromJson(validationResultJson,
                new TypeReference<List<RowValidationResult>>() {
                });
    }

    static List<Integer> errorRowIndices(List<RowValidationResult> validation, int rowCount) {
        return validation.stream()
                .filter(result -> result.errorMessage() != null)
                .map(RowValidationResult::row)
                .filter(row -> row >= 1 && row <= rowCount)
                .distinct()
                .sorted()
                .toList();
    }

    private PromptStrategy strategy(String type) {
        return PromptFactory.lookup(promptStrategies, type);
    }

    /**
     * ws20: the largest share of cells a non-bulk full-table edit may change —
     * the setting {@code chatEdit.fullTableMaxChangeRatio} of the caller's
     * bank, read per edit; with nothing stored the yml value.
     */
    public double maxChangeRatio() {
        return settings == null ? properties.getFullTableMaxChangeRatio()
                : settings.getDecimalForCurrentBank(SettingKeys.CHAT_EDIT_MAX_CHANGE_RATIO)
                .doubleValue();
    }
}
