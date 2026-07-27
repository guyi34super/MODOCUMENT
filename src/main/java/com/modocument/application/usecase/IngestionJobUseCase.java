package com.modocument.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.WizardStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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

    private final IngestionJobRepository jobs;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final ImportHistoryRepository importHistory;
    private final ImportDetailsRepository importDetails;
    private final IngestionParseStep parseStep;
    private final AutoMapUseCase autoMap;
    private final ValidateRowsUseCase validateRows;

    public IngestionJobUseCase(IngestionJobRepository jobs,
                               TemplateRepository templates,
                               TemplateFieldsRepository templateFields,
                               ImportHistoryRepository importHistory,
                               ImportDetailsRepository importDetails,
                               IngestionParseStep parseStep,
                               AutoMapUseCase autoMap,
                               ValidateRowsUseCase validateRows) {
        this.jobs = jobs;
        this.templates = templates;
        this.templateFields = templateFields;
        this.importHistory = importHistory;
        this.importDetails = importDetails;
        this.parseStep = parseStep;
        this.autoMap = autoMap;
        this.validateRows = validateRows;
    }

    public IngestionJob create(String fileName, int templateId) {
        templates.getById(templateId).orElseThrow(
                () -> new NoSuchElementException("Template " + templateId + " not found."));

        ImportHistory history = new ImportHistory();
        history.setCompanyId(1000);
        history.setFileName(fileName);
        history.setTemplateId(templateId);
        history.setImportDate(LocalDateTime.now(ZoneOffset.UTC));
        ImportHistory savedHistory = importHistory.add(history);

        IngestionJob job = new IngestionJob();
        job.setFileName(fileName);
        job.setTemplateId(templateId);
        job.setImportHistoryId(savedHistory.getImportHistoryId());
        job.setCurrentStep(JobStep.UPLOADED);
        job.setStatus(JobStatus.PENDING);
        return jobs.save(job);
    }

    public IngestionJob get(int jobId) {
        return jobs.getById(jobId).orElseThrow(
                () -> new NoSuchElementException("Ingestion job " + jobId + " not found."));
    }

    public List<IngestionJob> list() {
        return jobs.list();
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
        IngestionJob job = get(jobId);
        requireActive(job);

        // A previously failed job is resumable: clear the failure marker so the
        // per-stage guards below only react to failures from THIS run.
        if (job.getStatus() == JobStatus.FAILED) {
            job.setStatus(JobStatus.PENDING);
            job.setErrorMessage(null);
            job = jobs.save(job);
        }

        if (job.getCurrentStep().ordinal() >= until.ordinal()) {
            return job;
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
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (entry.getValue() != null && !fieldNames.contains(entry.getValue())) {
                throw new IllegalArgumentException(
                        "'" + entry.getValue() + "' is not a field of template " + job.getTemplateId() + ".");
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
        return jobs.save(job);
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
        return jobs.save(job);
    }

    /**
     * Checkpoints the user's wizard position so reopening the job resumes the
     * UI at the same pane. MAPPING is always allowed; VALIDATION and EXPORT
     * only once the job has validated. Idempotent last-write-wins.
     */
    public IngestionJob setWizardStep(int jobId, WizardStep step) {
        IngestionJob job = get(jobId);
        if (step != WizardStep.MAPPING && !hasValidated(job)) {
            throw new IllegalArgumentException(
                    "Wizard step " + step + " is not reachable while the job is at "
                            + job.getCurrentStep() + ".");
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
            return jobs.save(job);
        } catch (Exception e) {
            log.warn("Ingestion job {} failed during {} stage: {}",
                    job.getIngestionJobId(), target, e.getMessage());
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(abbreviate(e.getMessage()));
            return jobs.save(job);
        }
    }

    private void doParse(IngestionJob job) {
        TableData table = parseStep.parse(job.getFileName());
        if (table == null || table.isEmpty()) {
            throw new IllegalStateException(
                    "No tabular data could be extracted from '" + job.getFileName()
                            + "' (empty document, unsupported content, or OCR unavailable).");
        }
        job.setParsedTableJson(toJson(table));
        clearDownstream(job, JobStep.PARSED);
        recordImportDetails(job, STEP_TRANSFORMING, job.getParsedTableJson());
    }

    private void doMap(IngestionJob job) {
        TableData parsed = fromJson(job.getParsedTableJson(), TableData.class);
        Map<String, String> mapping = autoMap.map(parsed.getColumns(), templateFieldNames(job.getTemplateId()));
        job.setMappingJson(toJson(mapping));
        job.setMappedRowsJson(toJson(applyMapping(parsed, mapping)));
        clearDownstream(job, JobStep.MAPPED);
        recordImportDetails(job, STEP_MAPPING, job.getMappingJson());
    }

    private void doValidate(IngestionJob job) {
        TableData mapped = fromJson(job.getMappedRowsJson(), TableData.class);
        List<RowValidationResult> results =
                validateRows.handle(new ValidationRequest(job.getTemplateId(), mapped.getRows()));
        job.setValidationResultJson(toJson(results));
        recordImportDetails(job, STEP_VALIDATING, job.getValidationResultJson());
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

    private static String abbreviate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
