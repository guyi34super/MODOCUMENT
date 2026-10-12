package com.modocument.application.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.FileStore;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.IntegrationEventLog;
import com.modocument.application.port.integration.RecordsIngestion;
import com.modocument.application.privacy.PiiMaskingService;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.export.ExportGuard;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The read/write logic behind {@code /api/integration/v1/**} (ws26) for an
 * authenticated machine caller. Every id is checked against the CLIENT'S
 * bank (RLS does the same on PostgreSQL): another bank's id is a 404, never
 * a 403. Machine-created jobs stay owner-scoped (the client's creator) and
 * use bank-shared (BANK) templates only (contracts §7.1); a client reads
 * only jobs it could have created — its creator's jobs on BANK templates.
 */
@Service
public class IntegrationApiUseCase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final TemplateDataTypeRepository dataTypes;
    private final IngestionJobUseCase ingestion;
    private final RecordsIngestion recordsIngestion;
    private final IntegrationEventLog eventLog;
    private final PiiMaskingService masking;
    private final ExportGuard exportGuard;
    private final FileExportService fileExport;
    private final DocInstanceRepository documents;
    private final FileStore storage;

    public IntegrationApiUseCase(TemplateRepository templates,
                                 TemplateFieldsRepository templateFields,
                                 TemplateDataTypeRepository dataTypes,
                                 IngestionJobUseCase ingestion,
                                 RecordsIngestion recordsIngestion,
                                 IntegrationEventLog eventLog,
                                 PiiMaskingService masking,
                                 ExportGuard exportGuard,
                                 FileExportService fileExport,
                                 DocInstanceRepository documents,
                                 FileStore storage) {
        this.templates = templates;
        this.templateFields = templateFields;
        this.dataTypes = dataTypes;
        this.ingestion = ingestion;
        this.recordsIngestion = recordsIngestion;
        this.eventLog = eventLog;
        this.masking = masking;
        this.exportGuard = exportGuard;
        this.fileExport = fileExport;
        this.documents = documents;
        this.storage = storage;
    }

    // ================================================================ templates

    /** The bank-shared templates with their field names and types. */
    public List<Map<String, Object>> templates(ClientPrincipal client) {
        Map<Integer, String> typeNames = new HashMap<>();
        for (TemplateDataType type : dataTypes.list()) {
            typeNames.put(type.getTemplateDataTypeId(), type.getDataType());
        }
        List<Template> shared = templates.listAccessible(requireOwner(client), client.bankId()).stream()
                .filter(template -> isShared(template, client))
                .toList();
        // ws28: one fields query for every template, not one per template.
        Map<Integer, List<TemplateFields>> fieldsByTemplate = shared.isEmpty() ? Map.of()
                : templateFields.listByTemplateIds(shared.stream().map(Template::getTemplateId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Template template : shared) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (TemplateFields field : fieldsByTemplate.getOrDefault(template.getTemplateId(), List.of())) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("name", field.getFieldName());
                view.put("dataType", typeNames.get(field.getTemplateDataTypeId()));
                fields.add(view);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("templateId", template.getTemplateId());
            view.put("name", template.getName());
            view.put("description", template.getDescription());
            view.put("fields", fields);
            out.add(view);
        }
        return out;
    }

    /** A BANK template of the client's bank, else 404. */
    public Template requireSharedTemplate(ClientPrincipal client, int templateId) {
        return templates.getAccessible(templateId, requireOwner(client), client.bankId())
                .filter(template -> isShared(template, client))
                .orElseThrow(() -> IntegrationApiException.notFound("Template " + templateId));
    }

    // ================================================================ ingest

    /**
     * JSON records → a new ingestion job (SE4's {@link RecordsIngestion}),
     * owned by the client's creator, origin {@code api-client:<id>}.
     */
    public int createFromRecords(ClientPrincipal client, int templateId, String externalBatchId,
                                 List<Map<String, Object>> records) {
        requireSharedTemplate(client, templateId);
        return recordsIngestion.createFromRecords(new RecordsIngestion.Command(client.bankId(),
                requireOwner(client), templateId, externalBatchId, records, origin(client)));
    }

    public static String origin(ClientPrincipal client) {
        return "api-client:" + client.apiClientId();
    }

    // ================================================================ jobs

    /** A job the client may read: its creator's, in its bank, on a BANK template. */
    public IngestionJob requireJob(ClientPrincipal client, int jobId) {
        IngestionJob job;
        try {
            job = ingestion.get(jobId);
        } catch (NoSuchElementException missing) {
            throw IntegrationApiException.notFound("Job " + jobId);
        }
        if (job.getBankId() != client.bankId()
                || templates.getById(job.getTemplateId())
                .filter(template -> isShared(template, client)).isEmpty()) {
            throw IntegrationApiException.notFound("Job " + jobId);
        }
        return job;
    }

    /** {@code {jobId, status, currentStep, parsedBy, counts:{rows, valid, rejected}, errorMessage}}. */
    public Map<String, Object> jobStatus(ClientPrincipal client, int jobId) {
        IngestionJob job = requireJob(client, jobId);
        Split split = split(job);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("rows", split.table() == null ? null : split.table().getRows().size());
        counts.put("valid", split.validated() ? split.valid().size() : null);
        counts.put("rejected", split.validated() ? split.rejectedRows().size() : null);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job.getIngestionJobId());
        view.put("status", job.getStatus() == null ? null : job.getStatus().name());
        view.put("currentStep", job.getCurrentStep() == null ? null : job.getCurrentStep().name());
        view.put("parsedBy", job.getParsedBy());
        view.put("counts", counts);
        view.put("errorMessage", job.getErrorMessage() == null ? null
                : masking.maskReportText(job.getErrorMessage()));
        return view;
    }

    /** The validated rows, PII-masked (EXPORT channel), capped by the ExportGuard. */
    public Results results(ClientPrincipal client, int jobId, String format) {
        FileExportService.Format exportFormat = parseResultFormat(format);
        IngestionJob job = requireJob(client, jobId);
        Split split = requireValidated(job);
        TableData valid = new TableData(split.table().getColumns(), split.valid());
        ExportGuard.Attempt attempt = new ExportGuard.Attempt(jobId, "integration-api",
                exportFormat.name(), valid.getRows().size(), null);
        ExportGuard.Decision decision = exportGuard.authorize(attempt);
        TableData masked = masking.maskForExport(job.getTemplateId(), valid);
        exportGuard.recordExport(attempt, decision, masked.getRows().size());
        if (exportFormat == FileExportService.Format.CSV) {
            return new Results(null, fileExport.export(masked, exportFormat),
                    exportFormat.contentType(), "job" + jobId + "-results.csv");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("columns", masked.getColumns());
        body.put("rows", masked.getRows());
        body.put("count", masked.getRows().size());
        return new Results(body, null, null, null);
    }

    /** JSON body or file bytes of a results read. */
    public record Results(Map<String, Object> json, byte[] file, String contentType,
                         String fileName) {
    }

    /** Rejected rows with their reasons; row values PII-masked. */
    public Map<String, Object> errors(ClientPrincipal client, int jobId) {
        IngestionJob job = requireJob(client, jobId);
        Split split = requireValidated(job);
        TableData rejectedTable = new TableData(split.table().getColumns(),
                split.rejectedRows().values().stream().toList());
        TableData masked = masking.maskForExport(job.getTemplateId(), rejectedTable);
        List<Map<String, Object>> rejected = new ArrayList<>();
        int index = 0;
        for (Map.Entry<Integer, Map<String, String>> entry : split.rejectedRows().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row", entry.getKey());
            row.put("errors", split.errorsByRow().getOrDefault(entry.getKey(), List.of()));
            row.put("values", masked.getRows().get(index++));
            rejected.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("rejected", rejected);
        body.put("count", rejected.size());
        return body;
    }

    // ================================================================ documents

    /** A RELEASED document of the client's bank (metadata). */
    public Map<String, Object> document(ClientPrincipal client, int documentId) {
        DocInstance document = requireReleased(client, documentId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("documentId", document.getDocInstanceId());
        view.put("title", document.getTitle());
        view.put("status", document.getStatus());
        view.put("releasedAt", document.getReleasedAt());
        List<String> formats = new ArrayList<>();
        if (document.getLastGeneratedDocx() != null) {
            formats.add("docx");
        }
        if (document.getLastGeneratedPdf() != null) {
            formats.add("pdf");
        }
        view.put("formats", formats);
        return view;
    }

    /** The released document's file exactly as generated (never regenerated here). */
    public Results documentFile(ClientPrincipal client, int documentId, String format) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"docx".equalsIgnoreCase(format)) {
            throw IntegrationApiException.badRequest("INVALID_FORMAT", "format must be docx or pdf.");
        }
        DocInstance document = requireReleased(client, documentId);
        String fileName = pdf ? document.getLastGeneratedPdf() : document.getLastGeneratedDocx();
        if (fileName == null) {
            throw IntegrationApiException.notFound("A " + format.toLowerCase() + " file of document "
                    + documentId);
        }
        String key = FileStoreKeys.tryExport(fileName).orElseThrow(
                () -> IntegrationApiException.notFound("The file of document " + documentId));
        try (java.io.InputStream in = storage.open(key)) {
            return new Results(null, in.readAllBytes(),
                    pdf ? "application/pdf"
                            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "document" + documentId + (pdf ? ".pdf" : ".docx"));
        } catch (java.nio.file.NoSuchFileException e) {
            throw IntegrationApiException.notFound("The file of document " + documentId);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the document file.", e);
        }
    }

    private DocInstance requireReleased(ClientPrincipal client, int documentId) {
        return documents.getByIdAndBank(documentId, client.bankId())
                .filter(document -> DocInstance.STATUS_RELEASED.equals(document.getStatus()))
                .filter(document -> document.getContentPurgedAt() == null)
                .orElseThrow(() -> IntegrationApiException.notFound("Document " + documentId));
    }

    // ================================================================ events

    /** {@code {events:[{id, type, occurredAt, entity:{type, id}, data}], next}}. */
    public Map<String, Object> events(ClientPrincipal client, long after, int limit) {
        List<IntegrationEventLog.Event> page = eventLog.page(client.bankId(), Math.max(0, after),
                limit);
        List<Map<String, Object>> events = new ArrayList<>();
        long next = Math.max(0, after);
        for (IntegrationEventLog.Event event : page) {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("type", event.entityType());
            entity.put("id", event.entityId());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", event.id());
            view.put("type", event.type() == null ? null : event.type().name());
            view.put("occurredAt", event.occurredAt());
            view.put("entity", entity);
            view.put("data", event.data() == null ? Map.of() : event.data());
            events.add(view);
            next = Math.max(next, event.id());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", events);
        body.put("next", next);
        return body;
    }

    // ================================================================ plumbing

    private static boolean isShared(Template template, ClientPrincipal client) {
        return template.getBankId() == client.bankId()
                && template.getVisibility() == TemplateVisibility.BANK;
    }

    private static int requireOwner(ClientPrincipal client) {
        if (client.createdByUserId() <= 0) {
            throw IntegrationApiException.conflict("CLIENT_HAS_NO_OWNER",
                    "This API client acts for no user and cannot use the integration API.");
        }
        return client.createdByUserId();
    }

    private static FileExportService.Format parseResultFormat(String format) {
        if (format == null || format.isBlank() || "json".equalsIgnoreCase(format)) {
            return FileExportService.Format.JSON;
        }
        if ("csv".equalsIgnoreCase(format)) {
            return FileExportService.Format.CSV;
        }
        throw IntegrationApiException.badRequest("INVALID_FORMAT", "format must be json or csv.");
    }

    private Split requireValidated(IngestionJob job) {
        Split split = split(job);
        if (!split.validated() || split.table() == null) {
            throw IntegrationApiException.conflict("JOB_NOT_READY", "Job "
                    + job.getIngestionJobId() + " has not been validated yet (current step "
                    + job.getCurrentStep() + ").");
        }
        return split;
    }

    /** The mapped rows split by the validation outcome (rows are 1-based). */
    private Split split(IngestionJob job) {
        TableData table = read(job.getMappedRowsJson(), new TypeReference<TableData>() {
        });
        boolean validated = job.getCurrentStep() != null
                && job.getCurrentStep().ordinal() >= JobStep.VALIDATED.ordinal()
                && table != null;
        if (!validated) {
            return new Split(table, false, List.of(), new TreeMap<>(), Map.of());
        }
        List<RowValidationResult> results = read(job.getValidationResultJson(),
                new TypeReference<List<RowValidationResult>>() {
                });
        Map<Integer, List<Map<String, Object>>> errorsByRow = new TreeMap<>();
        if (results != null) {
            for (RowValidationResult result : results) {
                if (result.errorMessage() == null) {
                    continue;
                }
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("field", result.fieldName());
                error.put("message", result.errorMessage());
                error.put("rule", result.ruleType());
                errorsByRow.computeIfAbsent(result.row(), row -> new ArrayList<>()).add(error);
            }
        }
        Set<Integer> rejected = new TreeSet<>(errorsByRow.keySet());
        List<Map<String, String>> valid = new ArrayList<>();
        TreeMap<Integer, Map<String, String>> rejectedRows = new TreeMap<>();
        List<Map<String, String>> rows = table.getRows() == null ? List.of() : table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rejected.contains(i + 1)) {
                rejectedRows.put(i + 1, rows.get(i));
            } else {
                valid.add(rows.get(i));
            }
        }
        return new Split(table, true, valid, rejectedRows, errorsByRow);
    }

    private record Split(TableData table, boolean validated, List<Map<String, String>> valid,
                         TreeMap<Integer, Map<String, String>> rejectedRows,
                         Map<Integer, List<Map<String, Object>>> errorsByRow) {
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (IOException e) {
            return null;
        }
    }
}
