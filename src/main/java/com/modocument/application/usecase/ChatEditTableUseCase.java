package com.modocument.application.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.modocument.application.dto.ChatEditPatchPromptInput;
import com.modocument.application.dto.ChatEditPatchResponse;
import com.modocument.application.dto.ChatEditPromptInput;
import com.modocument.application.dto.RowPatch;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.PromptStrategy;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Chat-based table editing. When the job has validation errors, only the
 * failing rows (with their error messages) are sent to the LLM as a chunk and
 * the model returns a patch of modified rows that is merged back by row index
 * — a fraction of the tokens of a full-table round trip. Without validation
 * errors it falls back to sending the whole table. Validation and export
 * checkpoints are cleared because the data changed.
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

    public ChatEditTableUseCase(IngestionJobRepository jobs,
                                List<PromptStrategy> promptStrategies,
                                AiFilesService aiFilesService,
                                PiiTokenizer tokenizer,
                                com.modocument.application.port.CurrentUserProvider currentUser) {
        this.jobs = jobs;
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.tokenizer = tokenizer;
        this.currentUser = currentUser;
    }

    public IngestionJob handle(int jobId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required.");
        }
        // Owner-scoped: chat-editing another user's job is a 404, not an edit.
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
        // Validation results only describe mappedRows; ignore them when the
        // edit targets the parsed table.
        List<RowValidationResult> validation =
                editMapped ? readValidationResults(job.getValidationResultJson()) : List.of();
        List<Integer> errorRows = errorRowIndices(validation, current.getRows().size());

        // Sensitive values travel to the model only as placeholders; the
        // session holds the mapping and restores originals in the response.
        PiiTokenizer.Session session = tokenizer.newSession();
        TableData modified = errorRows.isEmpty()
                ? editFullTable(instruction, current, session)
                : editErrorRows(instruction, current, validation, errorRows, session);

        String modifiedJson = IngestionJobUseCase.toJson(modified);
        if (editMapped) {
            job.setMappedRowsJson(modifiedJson);
            job.setCurrentStep(JobStep.MAPPED);
        } else {
            job.setParsedTableJson(modifiedJson);
            job.setCurrentStep(JobStep.PARSED);
        }
        // Data changed: stale validation/export checkpoints no longer apply.
        job.setValidationResultJson(null);
        job.setExportInfoJson(null);
        IngestionJobUseCase.clampWizardStep(job);
        job.setStatus(JobStatus.COMPLETED);
        job.setErrorMessage(null);
        return jobs.save(job);
    }

    /** Original behavior: whole table in, whole table out (values tokenized in transit). */
    private TableData editFullTable(String instruction, TableData current, PiiTokenizer.Session session) {
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
                prompt, new ParameterizedTypeReference<TableData>() {
                });
        if (modified == null || modified.isEmpty()) {
            throw new IllegalStateException("The model returned an empty table; nothing was changed.");
        }
        return applyFullTable(current, modified, session);
    }

    /**
     * Rebuilds the table from the model's answer while keeping the schema and
     * the row count under our control: the persisted columns are the ones we
     * sent, values are copied only for keys we recognize, and a row count that
     * does not match means the model lost track of the table (dropped or
     * invented rows) — reject the whole edit rather than persist it.
     */
    private static TableData applyFullTable(TableData current, TableData modified,
                                            PiiTokenizer.Session session) {
        List<Map<String, String>> incoming = modified.getRows();
        if (incoming.size() != current.getRows().size()) {
            throw new IllegalStateException("The model returned " + incoming.size()
                    + " rows but the table has " + current.getRows().size()
                    + "; nothing was changed.");
        }
        // detokenizeRow walks a row's entries, so a JSON null row would NPE.
        List<Map<String, String>> sanitized = incoming.stream()
                .map(row -> row == null ? Map.<String, String>of() : row)
                .toList();
        List<Map<String, String>> restored = session.detokenizeRows(sanitized);

        List<Map<String, String>> rows = new ArrayList<>(restored.size());
        for (int i = 0; i < restored.size(); i++) {
            Map<String, String> answer = restored.get(i);
            Map<String, String> row = new LinkedHashMap<>(current.getRows().get(i));
            for (String column : current.getColumns()) {
                if (answer.containsKey(column)) {
                    String value = answer.get(column);
                    row.put(column, value == null ? "" : value);
                }
            }
            rows.add(row);
        }
        return new TableData(current.getColumns(), rows);
    }

    /** Chunked behavior: only the failing rows go to the model; its patch is merged back. */
    private TableData editErrorRows(String instruction, TableData current,
                                    List<RowValidationResult> validation, List<Integer> errorRows,
                                    PiiTokenizer.Session session) {
        if (errorRows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Table has more than " + MAX_ROWS + " rows needing fixes; chat editing is limited to "
                            + MAX_ROWS + " rows.");
        }

        List<Map<String, Object>> payload = new ArrayList<>();
        for (int rowNum : errorRows) {
            Map<String, String> errors = new LinkedHashMap<>();
            for (RowValidationResult result : validation) {
                if (result.row() == rowNum && result.errorMessage() != null) {
                    errors.put(result.fieldName(), result.errorMessage());
                }
            }
            payload.add(Map.of(
                    "row", rowNum,
                    "values", session.tokenizeRow(current.getRows().get(rowNum - 1)),
                    "errors", errors));
        }

        String prompt = strategy("ChatEditPatch").buildPrompt(new ChatEditPatchPromptInput(
                instruction,
                IngestionJobUseCase.toJson(current.getColumns()),
                IngestionJobUseCase.toJson(payload)));
        ChatEditPatchResponse patch = aiFilesService.sendForObject(
                prompt, new ParameterizedTypeReference<ChatEditPatchResponse>() {
                });
        if (patch == null || patch.rows() == null || patch.rows().isEmpty()) {
            throw new IllegalStateException("The model returned no row changes; nothing was changed.");
        }
        // Restore real values before merging so range/column validation and
        // the follow-up re-validation all operate on plaintext.
        List<RowPatch> detokenized = patch.rows().stream()
                .map(p -> new RowPatch(p.row(),
                        p.values() == null ? null : session.detokenizeRow(p.values())))
                .toList();
        return mergePatch(current, detokenized);
    }

    /**
     * Applies model-proposed row patches to a copy of the table. A row index
     * outside the table means the model lost positional grounding — reject
     * everything rather than risk corrupting the wrong rows. Unknown column
     * keys are ignored (common LLM noise; the follow-up re-validation is the
     * backstop).
     */
    private static TableData mergePatch(TableData current, List<RowPatch> patches) {
        List<Map<String, String>> rows = new ArrayList<>(current.getRows().size());
        for (Map<String, String> row : current.getRows()) {
            rows.add(new LinkedHashMap<>(row));
        }
        Set<String> columns = Set.copyOf(current.getColumns());

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
                if (!columns.contains(entry.getKey())) {
                    log.warn("Chat edit patch for row {} contains unknown column '{}'; ignoring it.",
                            patch.row(), entry.getKey());
                    continue;
                }
                rows.get(index).put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
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

    /** Sorted distinct 1-based row numbers that have at least one error, within table bounds. */
    private static List<Integer> errorRowIndices(List<RowValidationResult> validation, int rowCount) {
        return validation.stream()
                .filter(result -> result.errorMessage() != null)
                .map(RowValidationResult::row)
                .filter(row -> row >= 1 && row <= rowCount)
                .distinct()
                .sorted()
                .toList();
    }

    private PromptStrategy strategy(String type) {
        return promptStrategies.stream()
                .filter(s -> s.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(type + " prompt not registered."));
    }
}
