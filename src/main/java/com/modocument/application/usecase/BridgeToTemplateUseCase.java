package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.mapping.MappingContext;
import com.modocument.application.port.ExtractionAuditLog;
import com.modocument.application.port.ExtractionJobStore;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.document.ExtractionAuditEvent;
import com.modocument.domain.document.ProcessedRecord;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.AuditAction;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Hands extracted records to the existing document pipeline: streams
 * processed records in batches, optionally LLM-cleans them, auto-maps their
 * columns onto the template, validates with the rule engine, and persists a
 * standard IngestionJob (at VALIDATED) plus ImportHistory/ImportDetails audit
 * rows — so review, chat editing, and file/database export work unchanged.
 */
@Service
public class BridgeToTemplateUseCase {

    /** Seeded ProcessStep ids (same as IngestionJobUseCase). */
    private static final int STEP_TRANSFORMING = 1;
    private static final int STEP_MAPPING = 2;
    private static final int STEP_VALIDATING = 3;

    /** Default cap on rows materialized into the reviewable IngestionJob. */
    static final int DEFAULT_MAX_ROWS = 10_000;

    private final ExtractionJobStore extractionJobs;
    private final ProcessedRecordStore processedRecords;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final AutoMapUseCase autoMap;
    private final ValidateRowsUseCase validateRows;
    private final CleanRecordsUseCase cleanRecords;
    private final IngestionJobRepository ingestionJobs;
    private final ImportHistoryRepository importHistory;
    private final ImportDetailsRepository importDetails;
    private final ExtractionAuditLog audit;
    private final int batchSize;

    public record BridgeResult(int ingestionJobId, Integer importHistoryId, int rowsBridged,
                               long rowsAvailable, boolean truncated, int invalidValues,
                               Map<String, String> mapping) {
    }

    public BridgeToTemplateUseCase(ExtractionJobStore extractionJobs,
                                   ProcessedRecordStore processedRecords,
                                   TemplateRepository templates,
                                   TemplateFieldsRepository templateFields,
                                   AutoMapUseCase autoMap,
                                   ValidateRowsUseCase validateRows,
                                   CleanRecordsUseCase cleanRecords,
                                   IngestionJobRepository ingestionJobs,
                                   ImportHistoryRepository importHistory,
                                   ImportDetailsRepository importDetails,
                                   ExtractionAuditLog audit,
                                   @Value("${extraction.batch-size:500}") int batchSize) {
        this.extractionJobs = extractionJobs;
        this.processedRecords = processedRecords;
        this.templates = templates;
        this.templateFields = templateFields;
        this.autoMap = autoMap;
        this.validateRows = validateRows;
        this.cleanRecords = cleanRecords;
        this.ingestionJobs = ingestionJobs;
        this.importHistory = importHistory;
        this.importDetails = importDetails;
        this.audit = audit;
        this.batchSize = batchSize;
    }

    public BridgeResult bridge(String extractionJobId, int templateId, Integer maxRows,
                               boolean llmClean) {
        var extractionJob = extractionJobs.getById(extractionJobId).orElseThrow(
                () -> new NoSuchElementException(
                        "Extraction job " + extractionJobId + " not found."));
        if (extractionJob.getStatus() != ExtractionJobStatus.COMPLETED
                && extractionJob.getStatus() != ExtractionJobStatus.COMPLETED_WITH_ERRORS) {
            throw new IllegalStateException(
                    "Extraction job " + extractionJobId + " has not completed yet.");
        }
        templates.getById(templateId).orElseThrow(
                () -> new NoSuchElementException("Template " + templateId + " not found."));

        int cap = maxRows != null ? maxRows : DEFAULT_MAX_ROWS;
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        List<Map<String, String>> rows = new ArrayList<>();
        String afterId = null;
        while (rows.size() < cap) {
            List<ProcessedRecord> batch = processedRecords.findBatch(
                    extractionJobId, afterId, Math.min(batchSize, cap - rows.size()));
            if (batch.isEmpty()) {
                break;
            }
            afterId = batch.get(batch.size() - 1).getId();
            for (ProcessedRecord record : batch) {
                columns.addAll(record.getFields().keySet());
                rows.add(record.getFields());
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Extraction job " + extractionJobId + " has no processed records to bridge.");
        }
        long available = processedRecords.countByJob(extractionJobId);

        List<String> columnList = new ArrayList<>(columns);
        if (llmClean) {
            rows = cleanRecords.clean(columnList, rows, extractionJob.getDataSpec());
            audit.add(ExtractionAuditEvent.of(extractionJobId, extractionJob.getSourceId(),
                    AuditAction.LLM_CALL, null, "data cleaning over " + rows.size() + " row(s)",
                    null));
        }

        List<TemplateFields> fields = templateFields.listByTemplateId(templateId);
        TableData parsed = new TableData(columnList, rows);
        Map<String, String> mapping = autoMap.map(
                new MappingContext(columnList, fields, rows, templateId));

        TableData mapped = IngestionJobUseCase.applyMapping(parsed, mapping);
        List<RowValidationResult> results =
                validateRows.handle(new ValidationRequest(templateId, mapped.getRows()));
        int invalid = (int) results.stream().filter(r -> r.errorMessage() != null).count();

        String fileName = "extraction:" + extractionJobId;
        ImportHistory history = new ImportHistory();
        history.setCompanyId(1000);
        history.setFileName(fileName);
        history.setTemplateId(templateId);
        history.setImportDate(LocalDateTime.now(ZoneOffset.UTC));
        ImportHistory savedHistory = importHistory.add(history);

        IngestionJob ingestionJob = new IngestionJob();
        ingestionJob.setFileName(fileName);
        ingestionJob.setTemplateId(templateId);
        ingestionJob.setImportHistoryId(savedHistory.getImportHistoryId());
        ingestionJob.setParsedTableJson(IngestionJobUseCase.toJson(parsed));
        ingestionJob.setMappingJson(IngestionJobUseCase.toJson(mapping));
        ingestionJob.setMappedRowsJson(IngestionJobUseCase.toJson(mapped));
        ingestionJob.setValidationResultJson(IngestionJobUseCase.toJson(results));
        ingestionJob.setCurrentStep(JobStep.VALIDATED);
        ingestionJob.setStatus(JobStatus.COMPLETED);
        IngestionJob savedJob = ingestionJobs.save(ingestionJob);

        recordDetails(savedHistory.getImportHistoryId(), STEP_TRANSFORMING,
                ingestionJob.getParsedTableJson());
        recordDetails(savedHistory.getImportHistoryId(), STEP_MAPPING,
                ingestionJob.getMappingJson());
        recordDetails(savedHistory.getImportHistoryId(), STEP_VALIDATING,
                ingestionJob.getValidationResultJson());

        extractionJob.setTemplateId(templateId);
        extractionJobs.save(extractionJob);
        boolean truncated = rows.size() < available;
        audit.add(ExtractionAuditEvent.of(extractionJobId, extractionJob.getSourceId(),
                AuditAction.BRIDGE, null,
                rows.size() + " row(s) bridged to template " + templateId
                        + " as ingestion job " + savedJob.getIngestionJobId(),
                truncated ? "Truncated to " + rows.size() + " of " + available
                        + " records; pass maxRows to bridge more." : null));

        return new BridgeResult(savedJob.getIngestionJobId(), savedHistory.getImportHistoryId(),
                rows.size(), available, truncated, invalid, mapping);
    }

    private void recordDetails(Integer importHistoryId, int processStepId, String rawJson) {
        ImportDetails details = new ImportDetails();
        details.setImportHistoryId(importHistoryId);
        details.setProcessStepId(processStepId);
        details.setRawJson(rawJson);
        importDetails.add(details);
    }
}
