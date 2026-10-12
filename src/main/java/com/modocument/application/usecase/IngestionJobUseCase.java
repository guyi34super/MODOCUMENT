package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.IngestionJobCheckpoints;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.dto.IngestionJobDto;
import com.modocument.application.dto.IngestionJobSummary;
import com.modocument.application.dto.IngestionJobSummaryRow;
import com.modocument.application.dto.PagedList;
import com.modocument.application.dto.ParseContext;
import com.modocument.application.dto.ParseProvenance;
import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.mapping.MappingContext;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.privacy.LogSafe;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.ExtractionMode;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ai.AiErrorMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The resumable ingestion pipeline. Each stage persists its checkpoint and
 * advances CurrentStep before the next stage starts, so a crashed or failed
 * job resumes from the last completed stage — per-step commits ARE the
 * resumability (deliberately no single wrapping transaction).
 */
@Service
public class IngestionJobUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobUseCase.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Seeded ProcessStep ids: 1=Tansforming, 2=Mapping, 3=Validating. */
    private static final int STEP_TRANSFORMING = 1;
    private static final int STEP_MAPPING = 2;
    private static final int STEP_VALIDATING = 3;
    /** The shipped {@code extraction.code.minConfidence} (CodeTableExtractor.DEFAULT_MIN_CONFIDENCE). */
    static final double SHIPPED_MIN_CONFIDENCE = 0.75;

    private final IngestionJobRepository jobs;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final ImportHistoryRepository importHistory;
    private final ImportDetailsRepository importDetails;
    private final IngestionParseStep parseStep;
    private final AutoMapUseCase autoMap;
    private final ValidateRowsUseCase validateRows;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;
    /** ws25: the integration event stream (null in unit-test wiring = no events). */
    private final IntegrationEventLog eventLog;
    /** ws25: joins a job's terminal save and its event in one transaction (null = no tx). */
    private final TransactionTemplate eventTx;
    /** Wave 4: the bank's code-confidence threshold for job views (null in unit tests = shipped default). */
    private SettingsReader settings;

    /** Without an audit recorder (parse fallbacks are not audited) — unit tests. */
    public IngestionJobUseCase(IngestionJobRepository jobs,
                               TemplateRepository templates,
                               TemplateFieldsRepository templateFields,
                               ImportHistoryRepository importHistory,
                               ImportDetailsRepository importDetails,
                               IngestionParseStep parseStep,
                               AutoMapUseCase autoMap,
                               ValidateRowsUseCase validateRows,
                               CurrentUserProvider currentUser) {
        this(jobs, templates, templateFields, importHistory, importDetails, parseStep, autoMap,
                validateRows, currentUser, null);
    }

    /** With an audit recorder, no integration events — unit tests. */
    public IngestionJobUseCase(IngestionJobRepository jobs,
                               TemplateRepository templates,
                               TemplateFieldsRepository templateFields,
                               ImportHistoryRepository importHistory,
                               ImportDetailsRepository importDetails,
                               IngestionParseStep parseStep,
                               AutoMapUseCase autoMap,
                               ValidateRowsUseCase validateRows,
                               CurrentUserProvider currentUser,
                               AuditRecorder audit) {
        this(jobs, templates, templateFields, importHistory, importDetails, parseStep, autoMap,
                validateRows, currentUser, audit, null, null);
    }

    /**
     * ws25: a job reaching VALIDATED or FAILED publishes INGESTION_VALIDATED /
     * INGESTION_FAILED to the integration event log in the SAME transaction
     * as the job's terminal save (the outbox rows of subscribed outbound
     * flows commit with it, or not at all).
     */
    @Autowired
    public IngestionJobUseCase(IngestionJobRepository jobs,
                               TemplateRepository templates,
                               TemplateFieldsRepository templateFields,
                               ImportHistoryRepository importHistory,
                               ImportDetailsRepository importDetails,
                               IngestionParseStep parseStep,
                               AutoMapUseCase autoMap,
                               ValidateRowsUseCase validateRows,
                               CurrentUserProvider currentUser,
                               AuditRecorder audit,
                               IntegrationEventLog eventLog,
                               PlatformTransactionManager transactionManager) {
        this.eventLog = eventLog;
        this.eventTx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.audit = audit;
        this.jobs = jobs;
        this.templates = templates;
        this.templateFields = templateFields;
        this.importHistory = importHistory;
        this.importDetails = importDetails;
        this.parseStep = parseStep;
        this.autoMap = autoMap;
        this.validateRows = validateRows;
        this.currentUser = currentUser;
    }

    public IngestionJob create(String fileName, int templateId) {
        return create(fileName, templateId, null);
    }

    /**
     * ws25: {@link #create(String, int)} for a machine caller acting as its
     * owner (the integration API's {@code POST /files}): {@code origin}, e.g.
     * {@code api-client:<id>}, is stamped on the job (null = a person).
     */
    public IngestionJob create(String fileName, int templateId, String origin) {
        int owner = currentUser.requireUserId();
        int bankId = currentUser.requireBankId();
        // The template must exist AND be usable by the caller — their own, or
        // shared with their bank (ws22 read/use rule) — otherwise a user could
        // stamp their own jobs against someone else's private template.
        templates.getAccessible(templateId, owner, bankId).orElseThrow(
                () -> new NoSuchElementException("Template " + templateId + " not found."));

        ImportHistory history = new ImportHistory();
        history.setOwnerUserId(owner);
        history.setBankId(bankId);
        history.setFileName(fileName);
        history.setTemplateId(templateId);
        history.setImportDate(LocalDateTime.now(ZoneOffset.UTC));
        ImportHistory savedHistory = importHistory.add(history);

        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(owner);
        job.setBankId(bankId);
        job.setFileName(fileName);
        job.setTemplateId(templateId);
        job.setImportHistoryId(savedHistory.getImportHistoryId());
        job.setCurrentStep(JobStep.UPLOADED);
        job.setStatus(JobStatus.PENDING);
        job.setOrigin(origin == null ? null : abbreviateTo(origin, 100));
        IngestionJob saved = jobs.save(job);
        if (audit != null) {
            audit.success(AuditActions.INGESTION_JOB_CREATED, "IngestionJob",
                    String.valueOf(saved.getIngestionJobId()), null, Map.of(
                            "templateId", templateId, "fileName", fileName,
                            "origin", origin == null ? "user" : origin));
        }
        return saved;
    }

    /**
     * Owner-scoped fetch: a job the caller does not own is indistinguishable
     * from one that does not exist (404), never readable or mutable. Every
     * id-taking operation routes through here, so they all inherit the check.
     */
    public IngestionJob get(int jobId) {
        return jobs.getByIdAndOwner(jobId, currentUser.requireUserId()).orElseThrow(() -> {
            if (audit != null) { // ws28: owner-scoped 404 probe, ACCESS_DENIED (aggregated)
                audit.record(AuditActions.notFoundProbe("IngestionJob", jobId));
            }
            return new NoSuchElementException("Ingestion job " + jobId + " not found.");
        });
    }

    public List<IngestionJob> list() {
        return jobs.listByOwner(currentUser.requireUserId());
    }

    /**
     * Runs every remaining stage up to VALIDATED, starting from the job's
     * current step — creation with autoRun and /resume both land here.
     */
    public IngestionJob resume(int jobId) {
        return resume(jobId, JobStep.VALIDATED);
    }

    /**
     * Runs pipeline stages from the job's current step, stopping once
     * {@code until} is reached (inclusive). Use MAPPED to pause for mapping
     * review before validation.
     */
    public IngestionJob resume(int jobId, JobStep until) {
        return advance(get(jobId), until);
    }

    /** The stage runner behind {@link #resume}, for a job already resolved (and authorized). */
    private IngestionJob advance(IngestionJob job, JobStep until) {
        requireActive(job);

        if (job.getStatus() == JobStatus.RUNNING) {
            return job;
        }

        // Nothing to run: return before touching anything, so a no-op resume
        // cannot erase the recorded failure of an earlier one.
        if (job.getCurrentStep().ordinal() >= until.ordinal()) {
            return job;
        }

        // A previously failed job is resumable: clear the failure marker so the
        // per-stage guards below only react to failures from THIS run.
        if (job.getStatus() == JobStatus.FAILED) {
            job.setStatus(JobStatus.PENDING);
            job.setErrorMessage(null);
            job = jobs.save(job);
        }

        if (job.getCurrentStep() == JobStep.UPLOADED) {
            job = runStage(job, JobStep.PARSED, this::doParse);
            if (job.getStatus() == JobStatus.FAILED || job.getCurrentStep() == until) {
                return job;
            }
        }
        if (job.getStatus() != JobStatus.FAILED && job.getCurrentStep() == JobStep.PARSED) {
            job = runStage(job, JobStep.MAPPED, this::doMap);
            if (job.getStatus() == JobStatus.FAILED || job.getCurrentStep() == until) {
                return job;
            }
        }
        if (job.getStatus() != JobStatus.FAILED && job.getCurrentStep() == JobStep.MAPPED) {
            job = runStage(job, JobStep.VALIDATED, this::doValidate);
        }
        return job;
    }

    // ---- ws25: machine-created jobs (integration flows, API clients) ---------

    /**
     * JSON-records ingestion (ws25, {@code RecordsIngestion}): the records ARE
     * the parsed table, so parsing is skipped and the job runs map → validate
     * against the template. The job belongs to {@code ownerUserId} (the user
     * who created the flow or API client) in {@code bankId}, records its
     * {@code origin}, and may use a BANK-shared template of that bank only.
     */
    public IngestionJob createFromRecords(int bankId, int ownerUserId, int templateId,
                                          String externalBatchId,
                                          List<Map<String, Object>> records, String origin) {
        requireSharedTemplate(templateId, bankId);
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("At least one record is required.");
        }
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        List<Map<String, String>> rows = new java.util.ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, String> row = new LinkedHashMap<>();
            if (record != null) {
                for (Map.Entry<String, Object> cell : record.entrySet()) {
                    if (cell.getKey() == null || cell.getKey().isBlank()) {
                        continue;
                    }
                    columns.add(cell.getKey());
                    row.put(cell.getKey(), cellText(cell.getValue()));
                }
            }
            rows.add(row);
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("The records carry no fields.");
        }
        String label = externalBatchId == null || externalBatchId.isBlank() ? origin : externalBatchId;
        IngestionJob job = newMachineJob(bankId, ownerUserId, templateId,
                abbreviateTo("records:" + (label == null ? "batch" : label), 255), origin);
        job.setParsedTableJson(toJson(new TableData(new java.util.ArrayList<>(columns), rows)));
        job.setCurrentStep(JobStep.PARSED);
        job = jobs.save(job);
        recordImportDetails(job, STEP_TRANSFORMING, job.getParsedTableJson());
        return advance(job, JobStep.VALIDATED);
    }

    /**
     * A file a machine delivered (an SFTP pull), already stored in uploads/
     * under {@code storedName}: the normal pipeline from parse (AI or code
     * extraction) through validation, owned like {@link #createFromRecords}.
     */
    public IngestionJob createFromStoredFile(int bankId, int ownerUserId, int templateId,
                                             String storedName, String origin) {
        requireSharedTemplate(templateId, bankId);
        IngestionJob job = jobs.save(newMachineJob(bankId, ownerUserId, templateId, storedName, origin));
        return advance(job, JobStep.VALIDATED);
    }

    private void requireSharedTemplate(int templateId, int bankId) {
        templates.getById(templateId)
                .filter(template -> template.getBankId() == bankId && template.isShared())
                .orElseThrow(() -> new NoSuchElementException("Template " + templateId
                        + " not found (machine jobs use bank-shared templates only)."));
    }

    private IngestionJob newMachineJob(int bankId, int ownerUserId, int templateId,
                                       String fileName, String origin) {
        ImportHistory history = new ImportHistory();
        history.setOwnerUserId(ownerUserId);
        history.setBankId(bankId);
        history.setFileName(fileName);
        history.setTemplateId(templateId);
        history.setImportDate(LocalDateTime.now(ZoneOffset.UTC));
        ImportHistory savedHistory = importHistory.add(history);

        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(ownerUserId);
        job.setBankId(bankId);
        job.setFileName(fileName);
        job.setTemplateId(templateId);
        job.setImportHistoryId(savedHistory == null ? null : savedHistory.getImportHistoryId());
        job.setCurrentStep(JobStep.UPLOADED);
        job.setStatus(JobStatus.PENDING);
        job.setOrigin(origin == null ? null : abbreviateTo(origin, 100));
        return job;
    }

    private static String cellText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map || value instanceof java.util.Collection) {
            return toJson(value);
        }
        return String.valueOf(value);
    }

    private static String abbreviateTo(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Re-runs mapping on the parsed table; downstream checkpoints are cleared. */
    public IngestionJob remap(int jobId) {
        IngestionJob job = get(jobId);
        requireActive(job);
        if (job.getParsedTableJson() == null) {
            throw new IllegalStateException("Job has no parsed table yet; run parse first.");
        }
        job.setCurrentStep(JobStep.PARSED);
        clearDownstream(job, JobStep.PARSED);
        clampWizardStep(job);
        job = jobs.save(job);
        if (audit != null) {
            audit.success(AuditActions.INGESTION_REMAPPED, "IngestionJob", String.valueOf(jobId));
        }
        return runStage(job, JobStep.MAPPED, this::doMap);
    }

    /** Re-runs validation on the mapped rows. */
    public IngestionJob revalidate(int jobId) {
        IngestionJob job = get(jobId);
        requireActive(job);
        if (job.getMappedRowsJson() == null) {
            throw new IllegalStateException("Job has no mapped rows yet; run mapping first.");
        }
        job.setCurrentStep(JobStep.MAPPED);
        clearDownstream(job, JobStep.MAPPED);
        // Re-validation ends validated, so the Validation pane stays valid —
        // but the export it had is gone, and reopening on a stale Export pane
        // would offer a download of data that no longer exists.
        if (job.getWizardStep() == WizardStep.EXPORT) {
            job.setWizardStep(WizardStep.VALIDATION);
        }
        job = jobs.save(job);
        return runStage(job, JobStep.VALIDATED, this::doValidate);
    }

    /** Applies a user-reviewed mapping override and rebuilds the mapped rows. */
    public IngestionJob updateMapping(int jobId, Map<String, String> mapping) {
        IngestionJob job = get(jobId);
        requireActive(job);
        if (job.getParsedTableJson() == null) {
            throw new IllegalStateException("Job has no parsed table yet; run parse first.");
        }

        List<String> fieldNames = templateFieldNames(job.getTemplateId());
        Map<String, String> claimedBy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (!fieldNames.contains(entry.getValue())) {
                throw new IllegalArgumentException(
                        "'" + entry.getValue() + "' is not a field of template " + job.getTemplateId() + ".");
            }
            // applyMapping writes one column per field, so a second claim used
            // to drop a column's data with no warning at all.
            String previous = claimedBy.putIfAbsent(entry.getValue(), entry.getKey());
            if (previous != null) {
                throw new IllegalArgumentException("Both '" + previous + "' and '" + entry.getKey()
                        + "' are mapped to '" + entry.getValue()
                        + "'; each template field can take only one source column.");
            }
        }

        TableData parsed = fromJson(job.getParsedTableJson(), TableData.class);
        job.setMappingJson(toJson(mapping));
        job.setMappedRowsJson(toJson(applyMapping(parsed, mapping)));
        job.setCurrentStep(JobStep.MAPPED);
        clearDownstream(job, JobStep.MAPPED);
        clampWizardStep(job);
        job.setStatus(JobStatus.COMPLETED);
        job.setErrorMessage(null);
        IngestionJob remapped = jobs.save(job);
        if (audit != null) { // BUG-10: the manual mapping editor is a remap too; no cell values
            audit.success(AuditActions.INGESTION_REMAPPED, "IngestionJob", String.valueOf(jobId),
                    null, Map.of("source", "MANUAL", "mappedColumns", claimedBy.size()));
        }
        return remapped;
    }

    /** Persists directly-edited row values; downstream validation/export checkpoints are cleared. */
    public IngestionJob updateRows(int jobId, List<Map<String, String>> rows) {
        IngestionJob job = get(jobId);
        requireActive(job);
        if (job.getMappedRowsJson() == null) {
            throw new IllegalStateException("Job has no mapped rows yet; run mapping first.");
        }

        TableData current = fromJson(job.getMappedRowsJson(), TableData.class);
        if (rows.size() != current.getRows().size()) {
            throw new IllegalArgumentException("Expected " + current.getRows().size()
                    + " rows but got " + rows.size() + "; direct edits cannot add or remove rows.");
        }
        for (Map<String, String> row : rows) {
            for (String key : row.keySet()) {
                if (!current.getColumns().contains(key)) {
                    throw new IllegalArgumentException(
                            "'" + key + "' is not a column of the mapped table.");
                }
            }
        }

        job.setMappedRowsJson(toJson(new TableData(current.getColumns(), rows)));
        job.setCurrentStep(JobStep.MAPPED);
        clearDownstream(job, JobStep.MAPPED);
        clampWizardStep(job);
        job.setStatus(JobStatus.COMPLETED);
        job.setErrorMessage(null);
        IngestionJob edited = jobs.save(job);
        if (audit != null) { // ws28: counts only, never cell values
            audit.success(AuditActions.INGESTION_ROWS_EDITED, "IngestionJob",
                    String.valueOf(jobId), null, Map.of("rows", rows.size(),
                            "changedRows", changedRows(current.getRows(), rows)));
        }
        return edited;
    }

    /** Rows whose values differ between two tables (index-aligned); counts only, never values. */
    static int changedRows(List<Map<String, String>> before,
                           List<Map<String, String>> after) {
        int changed = 0;
        for (int i = 0; i < after.size(); i++) {
            if (i >= before.size() || !after.get(i).equals(before.get(i))) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Checkpoints the user's wizard position so reopening the job resumes the
     * UI at the same pane. Only a step the job has actually earned is accepted
     * — see {@link #reachableSteps}. Idempotent last-write-wins.
     */
    public IngestionJob setWizardStep(int jobId, WizardStep step) {
        IngestionJob job = get(jobId);
        requireActive(job);
        IngestionJobCheckpoints checkpoints = IngestionJobCheckpoints.of(job);
        List<String> unmapped = unmappedFields(checkpoints, templateFieldNames(job.getTemplateId()));
        if (!reachableSteps(checkpoints, unmapped).contains(step)) {
            throw new IllegalArgumentException("Wizard step " + step + " is not reachable yet: "
                    + blockedReason(checkpoints, unmapped, step));
        }
        job.setWizardStep(step);
        return jobs.save(job);
    }

    public IngestionJob cancel(int jobId) {
        IngestionJob job = get(jobId);
        job.setStatus(JobStatus.CANCELLED);
        return jobs.save(job);
    }

    /** Marks the export stage complete; called by the export endpoints. */
    public IngestionJob recordExport(IngestionJob job, Map<String, Object> exportInfo) {
        // A cancelled job must never be revived by an export: flipping status
        // back to COMPLETED here would undo the cancellation and wipe the
        // recorded reason.
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Job " + job.getIngestionJobId() + " is cancelled and cannot be exported.");
        }
        job.setExportInfoJson(toJson(exportInfo));
        job.setCurrentStep(JobStep.EXPORTED);
        job.setWizardStep(WizardStep.EXPORT);
        job.setStatus(JobStatus.COMPLETED);
        job.setErrorMessage(null);
        return jobs.save(job);
    }

    // ---- stages ------------------------------------------------------------

    private interface Stage {
        void run(IngestionJob job);
    }

    private IngestionJob runStage(IngestionJob job, JobStep target, Stage stage) {
        job.setStatus(JobStatus.RUNNING);
        job.setErrorMessage(null);
        job = jobs.save(job);
        try {
            stage.run(job);
            job.setCurrentStep(target);
            job.setStatus(JobStatus.COMPLETED);
            if (target == JobStep.VALIDATED) {
                return saveAndPublish(job, FlowEvent.INGESTION_VALIDATED, target);
            }
            return jobs.save(job);
        } catch (Exception e) {
            String message = toJobErrorMessage(e);
            log.warn("Ingestion job {} failed during {} stage: {}",
                    job.getIngestionJobId(), target, message);
            job.setStatus(JobStatus.FAILED);
            // ErrorMessage is persisted and later shown/exported, so it gets
            // the same PII/credential scrub as a log line (PRIV-005): a
            // parser or driver message can quote the very values that failed.
            job.setErrorMessage(LogSafe.scrub(abbreviate(message)));
            return saveAndPublish(job, FlowEvent.INGESTION_FAILED, target);
        }
    }

    /**
     * ws25: the terminal save and its integration event commit together
     * (transactional outbox). Without an event log (unit tests) it is the
     * plain save.
     */
    private IngestionJob saveAndPublish(IngestionJob job, FlowEvent event, JobStep step) {
        if (eventLog == null) {
            return jobs.save(job);
        }
        java.util.function.Supplier<IngestionJob> work = () -> {
            IngestionJob saved = jobs.save(job);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("jobId", saved.getIngestionJobId());
            data.put("templateId", saved.getTemplateId());
            data.put("status", saved.getStatus() == null ? null : saved.getStatus().name());
            data.put("step", step.name());
            if (event == FlowEvent.INGESTION_VALIDATED) {
                data.put("rejectedRows", rejectedRowCount(saved));
            }
            if (saved.getOrigin() != null) {
                data.put("origin", saved.getOrigin());
            }
            eventLog.publish(saved.getBankId(), event, "IngestionJob",
                    String.valueOf(saved.getIngestionJobId()), data);
            return saved;
        };
        return eventTx == null ? work.get() : eventTx.execute(status -> work.get());
    }

    /** Distinct rows with at least one validation failure. */
    private long rejectedRowCount(IngestionJob job) {
        if (job.getValidationResultJson() == null || job.getValidationResultJson().isBlank()) {
            return 0;
        }
        try {
            return readValidationResults(job).stream()
                    .filter(result -> result.errorMessage() != null)
                    .map(RowValidationResult::row)
                    .distinct()
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private void doParse(IngestionJob job) {
        ParseResult parsed = parseStep.parse(new ParseContext(
                job.getFileName(),
                job.getTemplateId(),
                job.getOwnerUserId(),
                templateFields.listByTemplateId(job.getTemplateId()),
                job.getBankId() > 0 ? job.getBankId() : null,
                templateExtractionMode(job.getTemplateId())));
        TableData table = parsed.table();
        if (table != null && table.getColumns() != null && !table.getColumns().isEmpty()
                && (table.getRows() == null || table.getRows().isEmpty())) {
            throw new IllegalStateException("'" + job.getFileName()
                    + "' has column headers but no data rows.");
        }
        if (table == null || table.isEmpty()) {
            throw new IllegalStateException(
                    "No tabular data could be extracted from '" + job.getFileName()
                            + "' (empty document, unsupported content, or OCR unavailable).");
        }
        job.setParsedTableJson(toJson(table));
        job.setSuggestedMappingJson(parsed.suggestedMapping().isEmpty()
                ? null : toJson(parsed.suggestedMapping()));
        recordProvenance(job, parsed.provenance());
        clearDownstream(job, JobStep.PARSED);
        recordImportDetails(job, STEP_TRANSFORMING, job.getParsedTableJson());
    }

    /** The template's own extraction mode (null = inherit the bank default). */
    private ExtractionMode templateExtractionMode(int templateId) {
        try {
            return templates.getById(templateId)
                    .map(Template::getExtractionMode)
                    .map(ExtractionMode::parseLenient)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Could not read the extraction mode of template {}: {}",
                    templateId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * ws23: stores who produced the parsed table on the job, and audits
     * INGESTION_PARSE_FALLBACK when code stood in for an AI that was meant to
     * run (labels and a score only — never data).
     */
    private void recordProvenance(IngestionJob job, ParseProvenance provenance) {
        job.setParsedBy(provenance == null ? null : provenance.parsedBy().name());
        job.setParseStrategy(provenance == null ? null : provenance.strategy());
        job.setParseConfidence(provenance == null || provenance.confidence() == null ? null
                : BigDecimal.valueOf(provenance.confidence()).setScale(3, RoundingMode.HALF_UP));
        job.setParseFallbackReason(provenance == null || provenance.fallbackReason() == null
                ? null : provenance.fallbackReason().name());
        if (audit == null || provenance == null || !provenance.isFallback()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("parsedBy", provenance.parsedBy().name());
        values.put("reason", provenance.fallbackReason().name());
        values.put("strategy", provenance.strategy());
        values.put("confidence", provenance.confidence());
        audit.success("INGESTION_PARSE_FALLBACK", "IngestionJob",
                String.valueOf(job.getIngestionJobId()), null, values);
    }

    private void doMap(IngestionJob job) {
        TableData parsed = fromJson(job.getParsedTableJson(), TableData.class);
        Map<String, String> suggested = readSuggestedMapping(job);
        Map<String, String> mapping = autoMap.map(new MappingContext(
                parsed.getColumns(),
                templateFields.listByTemplateId(job.getTemplateId()),
                parsed.getRows(),
                job.getTemplateId(),
                job.getOwnerUserId(),
                suggested));
        job.setMappingJson(toJson(mapping));
        job.setMappedRowsJson(toJson(applyMapping(parsed, mapping)));
        clearDownstream(job, JobStep.MAPPED);
        recordImportDetails(job, STEP_MAPPING, job.getMappingJson());
    }

    private Map<String, String> readSuggestedMapping(IngestionJob job) {
        if (job.getSuggestedMappingJson() == null || job.getSuggestedMappingJson().isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> suggested = fromJson(job.getSuggestedMappingJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
            return suggested == null ? Map.of() : suggested;
        } catch (Exception e) {
            log.warn("Could not read suggested mapping of job {}: {}",
                    job.getIngestionJobId(), e.getMessage());
            return Map.of();
        }
    }

    private void doValidate(IngestionJob job) {
        TableData mapped = fromJson(job.getMappedRowsJson(), TableData.class);
        List<RowValidationResult> results =
                validateRows.handle(new ValidationRequest(job.getTemplateId(), mapped.getRows()));
        // Only the problems are checkpointed. The engine reports one result per
        // field per row, which for a 10k-row file against a 40-field template is
        // 400k objects written into one column and shipped to the browser on
        // every job read — while nothing consumes the passing entries.
        List<RowValidationResult> problems = results.stream()
                .filter(result -> result.errorMessage() != null)
                .toList();
        job.setValidationResultJson(toJson(problems));
        // ws28: the list summary reads this count instead of the JSON.
        job.setValidationErrorCount(problems.size());
        recordImportDetails(job, STEP_VALIDATING, job.getValidationResultJson());
    }

    // ---- step completion ---------------------------------------------------
    //
    // ws28: every gate reads the job's checkpoints through ONE
    // IngestionJobCheckpoints memo per request, so building a view parses
    // each JSON column once (it used to parse MappedRowsJson three times and
    // ValidationResultJson twice).

    /**
     * Template fields that no source column feeds. The mapped table's columns
     * ARE the mapped fields ({@link #applyMapping} builds them from the distinct
     * mapping targets), so this is an exact answer; before mapping runs, every
     * field counts as unmapped.
     */
    public List<String> unmappedFields(IngestionJob job) {
        return unmappedFields(IngestionJobCheckpoints.of(job), templateFieldNames(job.getTemplateId()));
    }

    private static List<String> unmappedFields(IngestionJobCheckpoints checkpoints,
                                               List<String> templateFieldNames) {
        List<String> mapped = checkpoints.mappedColumns();
        return templateFieldNames.stream().filter(field -> !mapped.contains(field)).toList();
    }

    /** Rows that still fail validation. Zero is what finishes the step. */
    public long validationErrorCount(IngestionJob job) {
        return validationErrorCount(IngestionJobCheckpoints.of(job));
    }

    private static long validationErrorCount(IngestionJobCheckpoints checkpoints) {
        IngestionJob job = checkpoints.job();
        if (job.getValidationResultJson() == null || job.getValidationResultJson().isBlank()) {
            return 0;
        }
        // Only failures are persisted now, but jobs validated before that
        // change also hold the passing entries — the memo filters either way.
        Long count = checkpoints.validationErrorCount();
        if (count == null) {
            log.warn("Could not read validation results of job {}", job.getIngestionJobId());
            return 0;
        }
        return count;
    }

    private List<RowValidationResult> readValidationResults(IngestionJob job) {
        return fromJson(job.getValidationResultJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<RowValidationResult>>() {
                });
    }

    /**
     * The wizard panes this job has earned — the one rule the whole app defers
     * to, exposed on {@link com.modocument.application.dto.IngestionJobDto} so
     * the UI renders it instead of re-deriving it.
     *
     * <p>A step you have not finished does not open the next one: mapping is
     * finished when every template field has a source column, validation when
     * it has run with no failing rows.
     *
     * <p>Status plays no part: a cancelled job stays readable, and writing to
     * one is refused by {@link #requireActive} instead.
     */
    public List<WizardStep> reachableSteps(IngestionJob job) {
        IngestionJobCheckpoints checkpoints = IngestionJobCheckpoints.of(job);
        return reachableSteps(checkpoints,
                unmappedFields(checkpoints, templateFieldNames(job.getTemplateId())));
    }

    private static List<WizardStep> reachableSteps(IngestionJobCheckpoints checkpoints,
                                                   List<String> unmappedFields) {
        if (!mappingComplete(checkpoints, unmappedFields) || !hasValidated(checkpoints.job())) {
            return List.of(WizardStep.MAPPING);
        }
        if (validationErrorCount(checkpoints) > 0) {
            return List.of(WizardStep.MAPPING, WizardStep.VALIDATION);
        }
        return List.of(WizardStep.MAPPING, WizardStep.VALIDATION, WizardStep.EXPORT);
    }

    /** Why {@code step} is closed — phrased for the person who hit the wall. */
    public String blockedReason(IngestionJob job, WizardStep step) {
        IngestionJobCheckpoints checkpoints = IngestionJobCheckpoints.of(job);
        return blockedReason(checkpoints,
                unmappedFields(checkpoints, templateFieldNames(job.getTemplateId())), step);
    }

    private static String blockedReason(IngestionJobCheckpoints checkpoints, List<String> unmapped,
                                        WizardStep step) {
        if (!unmapped.isEmpty()) {
            return "map every template field first — " + unmapped.size() + " still unmapped ("
                    + String.join(", ", unmapped) + ")";
        }
        if (!mappingComplete(checkpoints, unmapped)) {
            return "map at least one column to a template field first";
        }
        if (!hasValidated(checkpoints.job())) {
            return "run validation on the mapped rows first";
        }
        long errors = validationErrorCount(checkpoints);
        if (step == WizardStep.EXPORT && errors > 0) {
            return errors + (errors == 1
                    ? " row still fails validation; fix it"
                    : " rows still fail validation; fix them")
                    + " on the validation step first";
        }
        return "the step is not available for this job";
    }

    /** API view of a job, carrying the step gates the UI renders. */
    public IngestionJobDto view(IngestionJob job) {
        return view(job, templateFieldNames(job.getTemplateId()));
    }

    /** Views for a whole list, looking each distinct template's fields up once. */
    public List<IngestionJobDto> views(List<IngestionJob> list) {
        Map<Integer, List<String>> fieldsByTemplate = new LinkedHashMap<>();
        return list.stream()
                .map(job -> view(job, fieldsByTemplate.computeIfAbsent(
                        job.getTemplateId(), this::templateFieldNames)))
                .toList();
    }

    private IngestionJobDto view(IngestionJob job, List<String> templateFieldNames) {
        IngestionJobCheckpoints checkpoints = IngestionJobCheckpoints.of(job);
        List<String> unmapped = unmappedFields(checkpoints, templateFieldNames);
        List<String> reachable = reachableSteps(checkpoints, unmapped).stream()
                .map(WizardStep::name)
                .toList();
        IngestionJobDto view = IngestionJobDto.from(checkpoints, unmapped, reachable);
        return job.getParseConfidence() == null ? view
                : view.withConfidenceThreshold(minConfidence(job.getBankId()));
    }

    // ---- ws28: the paged job list summary -----------------------------------

    /** The most jobs the legacy unpaged list returns. */
    public static final int LEGACY_LIST_CAP = 200;

    /**
     * One page of the caller's jobs as {@link IngestionJobSummary} rows,
     * newest first ({@code GET /api/ingestion/jobs?view=summary}). One count,
     * one projection query (no checkpoint JSON) and one query for the field
     * names of the page's templates — whatever the page size.
     */
    public PagedList<IngestionJobSummary> summaries(Integer page, Integer size) {
        int owner = currentUser.requireUserId();
        int pageNumber = PagedList.page(page);
        int pageSize = PagedList.size(size);
        long total = jobs.countByOwner(owner);
        List<IngestionJobSummaryRow> rows = total == 0 ? List.of()
                : jobs.listSummariesByOwner(owner, pageNumber, pageSize);
        return new PagedList<>(toSummaries(rows), pageNumber, pageSize, total);
    }

    /** The caller's {@code limit} newest jobs as summaries (the dashboard). */
    public List<IngestionJobSummary> recentSummaries(int limit) {
        return toSummaries(jobs.listSummariesByOwner(currentUser.requireUserId(), 0, limit));
    }

    /**
     * The legacy unpaged list (full views): kept for compatibility but capped
     * at the {@value #LEGACY_LIST_CAP} newest jobs, in the old (oldest-first)
     * order. The UI moved to {@link #summaries}.
     */
    public List<IngestionJob> listCapped() {
        List<IngestionJob> newest = new java.util.ArrayList<>(
                jobs.listNewestByOwner(currentUser.requireUserId(), LEGACY_LIST_CAP));
        java.util.Collections.reverse(newest);
        return newest;
    }

    private List<IngestionJobSummary> toSummaries(List<IngestionJobSummaryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<String>> fieldsByTemplate = templateFields.fieldNamesByTemplateIds(
                rows.stream().map(IngestionJobSummaryRow::templateId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return rows.stream()
                .map(row -> toSummary(row, fieldsByTemplate.getOrDefault(row.templateId(), List.of())))
                .toList();
    }

    private IngestionJobSummary toSummary(IngestionJobSummaryRow row, List<String> fieldNames) {
        Double confidence = row.parseConfidence() == null ? null : row.parseConfidence().doubleValue();
        // The full view's rule (IngestionJobDto.withConfidenceThreshold): only
        // a code score is judged, against the bank's current threshold.
        Boolean low = confidence == null || "AI".equals(row.parsedBy()) ? null
                : confidence < minConfidence(currentUser.requireBankId());
        return new IngestionJobSummary(
                row.ingestionJobId(),
                row.fileName(),
                row.templateId(),
                row.templateName(),
                row.status() == null ? null : row.status().name(),
                row.currentStep() == null ? null : row.currentStep().name(),
                row.parsedBy(),
                row.parseFallbackReason(),
                confidence,
                low,
                row.validationErrorCount(),
                unmappedFieldCount(row, fieldNames),
                row.createdDate(),
                row.updatedDate());
    }

    /**
     * The template fields no source column feeds. The mapped table's columns
     * are exactly the distinct non-blank mapping targets ({@link #applyMapping}),
     * so the small MappingJson answers what the full view reads from the
     * mapped rows; without mapped rows every field is unmapped.
     */
    static int unmappedFieldCount(IngestionJobSummaryRow row, List<String> fieldNames) {
        if (!row.hasMappedRows() || row.mappingJson() == null || row.mappingJson().isBlank()) {
            return fieldNames.size();
        }
        java.util.Set<String> mapped = new java.util.HashSet<>();
        try {
            Map<String, String> mapping = fromJson(row.mappingJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
            if (mapping != null) {
                mapping.values().stream().filter(v -> v != null && !v.isBlank()).forEach(mapped::add);
            }
        } catch (RuntimeException e) {
            log.debug("Unreadable mapping on job {}", row.ingestionJobId());
        }
        return (int) fieldNames.stream().filter(field -> !mapped.contains(field)).count();
    }

    /** Wave 4: wired by Spring; unit tests without it judge against the shipped 0.75. */
    @Autowired(required = false)
    public void setSettingsReader(SettingsReader settings) {
        this.settings = settings;
    }

    /**
     * The bank's {@code extraction.code.minConfidence} (in-memory snapshot
     * read). An unreadable setting never breaks a job view: the shipped
     * default stands in.
     */
    private double minConfidence(int bankId) {
        if (settings != null) {
            try {
                BigDecimal value = settings.getDecimal(SettingKeys.EXTRACTION_CODE_MIN_CONFIDENCE, bankId);
                if (value != null) {
                    return value.doubleValue();
                }
            } catch (RuntimeException e) {
                log.debug("extraction.code.minConfidence unreadable for bank {}: {}", bankId,
                        e.getClass().getSimpleName());
            }
        }
        return SHIPPED_MIN_CONFIDENCE;
    }

    /** Exports operate on finished work only; the message names the blocker. */
    public void requireExportable(IngestionJob job) {
        requireActive(job);
        IngestionJobCheckpoints checkpoints = IngestionJobCheckpoints.of(job);
        List<String> unmapped = unmappedFields(checkpoints, templateFieldNames(job.getTemplateId()));
        if (!reachableSteps(checkpoints, unmapped).contains(WizardStep.EXPORT)) {
            throw new IllegalStateException("Job " + job.getIngestionJobId()
                    + " cannot be exported yet: " + blockedReason(checkpoints, unmapped,
                    WizardStep.EXPORT) + ".");
        }
    }

    private static boolean mappingComplete(IngestionJobCheckpoints checkpoints,
                                           List<String> unmappedFields) {
        return !checkpoints.mappedColumns().isEmpty() && unmappedFields.isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    private void requireActive(IngestionJob job) {
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Job " + job.getIngestionJobId() + " is cancelled.");
        }
    }

    private static boolean hasValidated(IngestionJob job) {
        return job.getCurrentStep() == JobStep.VALIDATED || job.getCurrentStep() == JobStep.EXPORTED;
    }

    /**
     * Called after mutations that invalidate validation: a persisted wizard
     * position past mapping no longer points at a usable pane.
     */
    static void clampWizardStep(IngestionJob job) {
        if (job.getWizardStep() != null && job.getWizardStep() != WizardStep.MAPPING) {
            job.setWizardStep(WizardStep.MAPPING);
        }
    }

    private void clearDownstream(IngestionJob job, JobStep completedStep) {
        if (completedStep.ordinal() < JobStep.MAPPED.ordinal()) {
            job.setMappingJson(null);
            job.setMappedRowsJson(null);
        }
        if (completedStep.ordinal() < JobStep.VALIDATED.ordinal()) {
            job.setValidationResultJson(null);
        }
        job.setExportInfoJson(null);
    }

    private List<String> templateFieldNames(int templateId) {
        return templateFields.listByTemplateId(templateId).stream()
                .map(TemplateFields::getFieldName)
                .toList();
    }

    /** Rows keyed by template field; unmapped columns are dropped. */
    static TableData applyMapping(TableData parsed, Map<String, String> mapping) {
        List<String> targetColumns = mapping.values().stream()
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
        List<Map<String, String>> mappedRows = parsed.getRows().stream()
                .map(row -> {
                    Map<String, String> mappedRow = new LinkedHashMap<String, String>();
                    for (Map.Entry<String, String> entry : mapping.entrySet()) {
                        if (entry.getValue() != null && !entry.getValue().isBlank()) {
                            mappedRow.put(entry.getValue(), row.getOrDefault(entry.getKey(), ""));
                        }
                    }
                    return mappedRow;
                })
                .toList();
        return new TableData(targetColumns, mappedRows);
    }

    private void recordImportDetails(IngestionJob job, int processStepId, String rawJson) {
        if (job.getImportHistoryId() == null) {
            return;
        }
        ImportDetails details = new ImportDetails();
        details.setImportHistoryId(job.getImportHistoryId());
        details.setBankId(job.getBankId());
        details.setProcessStepId(processStepId);
        details.setRawJson(rawJson);
        importDetails.add(details);
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize checkpoint payload.", e);
        }
    }

    static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize checkpoint payload.", e);
        }
    }

    static <T> T fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize checkpoint payload.", e);
        }
    }

    private static String toJobErrorMessage(Exception e) {
        String friendly = AiErrorMessages.friendlyMessage(e);
        if (!friendly.equals(e.getMessage())) {
            return friendly;
        }
        return abbreviate(e.getMessage());
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
