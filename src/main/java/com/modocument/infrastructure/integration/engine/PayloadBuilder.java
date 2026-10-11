package com.modocument.infrastructure.integration.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.integration.OutboundBatch;
import com.modocument.application.privacy.Masker;
import com.modocument.application.privacy.PiiMaskingService;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.IntegrationEvent;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.OutboundDelivery;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import com.modocument.infrastructure.export.ExportGuard;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.persistence.IntegrationEventJpaRepository;
import com.modocument.infrastructure.settings.IntegrationSettings;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Builds the payload of one outbound delivery at dispatch time (ws25) — the
 * outbox stores only a reference ({@code job:<id>}, {@code doc:<id>},
 * {@code event:<id>}, {@code events:<from>-<to>}):
 * <ul>
 *   <li>{@code VALIDATED_RECORDS}: the job's mapped rows that passed
 *       validation; {@code REJECTED_ROWS}: the failing rows plus
 *       {@code _row} and {@code _errors:[{field, message}]};</li>
 *   <li>{@code JOB_STATUS}: {@code {jobId, templateId, status, currentStep,
 *       parsedBy, counts:{rows, valid, rejected}, errorMessage, origin}};</li>
 *   <li>{@code RELEASED_DOCUMENTS}: the released document's generated file
 *       (PDF when the flow's format is PDF and one exists, else DOCX);</li>
 *   <li>{@code EVENTS}: {@code {id, type, occurredAt, entityType, entityId, data}}.</li>
 * </ul>
 * Record payloads are PII-masked through {@link PiiMaskingService#maskForExport}
 * unless the flow turned masking off (a maker/checker decision), mapped to
 * the bank's names by the flow's field mapping, and capped: the flow's
 * RowCap, the bank's {@code integration.maxRowsPerRun} and the ADMIN export
 * cap of {@link ExportGuard} (a machine cannot state a reason, so a payload
 * above any cap is refused, never truncated). A delivered record payload is
 * logged as {@code EXPORT_RUN} with the flow id.
 */
@Component
public class PayloadBuilder {

    /** A payload that can never be built (cap, missing job): dead-lettered without retries. */
    public static class PayloadRefusedException extends RuntimeException {
        public PayloadRefusedException(String message) {
            super(message);
        }
    }

    private final IngestionJobRepository jobs;
    private final PiiMaskingService masking;
    private final ExportGuard exportGuard;
    private final IntegrationSettings settings;
    private final FileExportService files;
    private final DocInstanceRepository documents;
    private final StorageProperties storage;
    private final IntegrationEventJpaRepository events;
    private final AuditRecorder audit;

    public PayloadBuilder(IngestionJobRepository jobs, PiiMaskingService masking,
                          ExportGuard exportGuard, IntegrationSettings settings,
                          FileExportService files, DocInstanceRepository documents,
                          StorageProperties storage, IntegrationEventJpaRepository events,
                          AuditRecorder audit) {
        this.jobs = jobs;
        this.masking = masking;
        this.exportGuard = exportGuard;
        this.settings = settings;
        this.files = files;
        this.documents = documents;
        this.storage = storage;
        this.events = events;
        this.audit = audit;
    }

    /**
     * The batch for one delivery. {@code fileShaped}: the target takes files
     * (SFTP), so record payloads are serialized in the flow's format.
     */
    public OutboundBatch build(IntegrationFlow flow, OutboundDelivery delivery, boolean fileShaped) {
        PayloadKind kind = delivery.getPayloadKind();
        String ref = delivery.getPayloadRef();
        List<Map<String, Object>> records;
        switch (kind) {
            case VALIDATED_RECORDS -> records = validatedRecords(flow, job(flow, ref));
            case REJECTED_ROWS -> records = rejectedRows(flow, job(flow, ref));
            case JOB_STATUS -> records = List.of(jobStatus(job(flow, ref)));
            case EVENTS -> records = events(flow, ref);
            case RELEASED_DOCUMENTS -> {
                return releasedDocument(flow, ref);
            }
            default -> throw new PayloadRefusedException("Unknown payload kind.");
        }
        if (kind == PayloadKind.VALIDATED_RECORDS || kind == PayloadKind.REJECTED_ROWS) {
            records = IntegrationJson.applyMapping(records, IntegrationJson.mapping(flow.getFieldMappingJson()));
        }
        String baseName = kind.name().toLowerCase().replace('_', '-') + "-"
                + ref.replace(':', '-');
        if (!fileShaped) {
            return new OutboundBatch(null, kind, PayloadFormat.JSON, records, baseName + ".json",
                    "application/json", null);
        }
        FileExportService.Format format = switch (flow.getFormat()) {
            case CSV -> FileExportService.Format.CSV;
            case XLSX -> FileExportService.Format.XLSX;
            case JSON -> FileExportService.Format.JSON;
            case PDF -> throw new PayloadRefusedException("PDF is available for released documents only.");
        };
        byte[] content = files.export(table(records), format);
        return new OutboundBatch(null, kind, flow.getFormat(), records,
                baseName + format.extension(), format.contentType(), content);
    }

    /**
     * What the EXPORT_RUN row of a delivered payload records: the format, the
     * row count and — for a delivery the on-prem agent wrote — the agent id
     * (null for DIRECT).
     */
    public record ExportFacts(PayloadFormat format, int rowCount, Integer agentId) {

        /** A DIRECT delivery: the facts of the batch the dispatcher wrote. */
        public static ExportFacts of(OutboundBatch batch) {
            return new ExportFacts(batch.format(), batch.records().size(), null);
        }
    }

    /** The EXPORT_RUN row of a delivered record payload (the export log, ws18). */
    public void recordExport(IntegrationFlow flow, OutboundDelivery delivery, OutboundBatch batch) {
        recordExport(flow, delivery, ExportFacts.of(batch));
    }

    /**
     * The EXPORT_RUN row of a delivered record payload, DIRECT or through the
     * agent: entity {@code IntegrationFlow:<flowId>}, with the delivery, the
     * payload reference, the format, kind, row count, cap and masking.
     */
    public void recordExport(IntegrationFlow flow, OutboundDelivery delivery, ExportFacts facts) {
        if (delivery.getPayloadKind() != PayloadKind.VALIDATED_RECORDS
                && delivery.getPayloadKind() != PayloadKind.REJECTED_ROWS) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("flowId", flow.getId());
        values.put("deliveryId", delivery.getId());
        values.put("payloadRef", delivery.getPayloadRef());
        values.put("type", "integration");
        values.put("format", facts.format() == null ? null : facts.format().name());
        values.put("kind", delivery.getPayloadKind().name());
        values.put("rowCount", facts.rowCount());
        values.put("mode", facts.agentId() == null ? "DIRECT" : "AGENT");
        if (facts.agentId() != null) {
            values.put("agentId", facts.agentId());
        }
        values.put("cap", exportGuard.capFor(Role.ADMIN, flow.getBankId()));
        values.put("masked", flow.isMaskPii());
        audit.record(AuditEventSpec.builder(ExportGuard.EXPORT_RUN, AuditOutcome.SUCCESS)
                .entity("IntegrationFlow", String.valueOf(flow.getId()))
                .newValues(values)
                .build());
    }

    // ---- kinds --------------------------------------------------------------------

    private IngestionJob job(IntegrationFlow flow, String ref) {
        int jobId = parseId(ref, "job:");
        return jobs.getById(jobId)
                .filter(job -> job.getBankId() == flow.getBankId())
                .orElseThrow(() -> new PayloadRefusedException("Ingestion job " + jobId
                        + " no longer exists."));
    }

    private List<Map<String, Object>> validatedRecords(IntegrationFlow flow, IngestionJob job) {
        TableData mapped = mapped(job);
        Set<Integer> rejected = rejectedRowNumbers(job);
        List<Map<String, String>> kept = new ArrayList<>();
        for (int i = 0; i < mapped.getRows().size(); i++) {
            if (!rejected.contains(i + 1)) {
                kept.add(mapped.getRows().get(i));
            }
        }
        enforceCaps(flow, kept.size());
        TableData table = mask(flow, job, new TableData(mapped.getColumns(), kept));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, String> row : table.getRows()) {
            records.add(new LinkedHashMap<>(row));
        }
        return records;
    }

    private List<Map<String, Object>> rejectedRows(IntegrationFlow flow, IngestionJob job) {
        TableData mapped = mapped(job);
        Map<Integer, List<Map<String, Object>>> errorsByRow = new LinkedHashMap<>();
        for (RowValidationResult result : validationResults(job)) {
            if (result.errorMessage() == null) {
                continue;
            }
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("field", result.fieldName());
            error.put("message", flow.isMaskPii() ? Masker.maskShapes(result.errorMessage())
                    : result.errorMessage());
            errorsByRow.computeIfAbsent(result.row(), k -> new ArrayList<>()).add(error);
        }
        enforceCaps(flow, errorsByRow.size());
        List<Map<String, String>> rows = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (Integer row : errorsByRow.keySet()) {
            if (row >= 1 && row <= mapped.getRows().size()) {
                rows.add(mapped.getRows().get(row - 1));
                numbers.add(row);
            }
        }
        TableData masked = mask(flow, job, new TableData(mapped.getColumns(), rows));
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < masked.getRows().size(); i++) {
            Map<String, Object> record = new LinkedHashMap<>(masked.getRows().get(i));
            record.put("_row", numbers.get(i));
            record.put("_errors", errorsByRow.get(numbers.get(i)));
            records.add(record);
        }
        return records;
    }

    Map<String, Object> jobStatus(IngestionJob job) {
        long rows = job.getMappedRowsJson() == null ? 0 : mapped(job).getRows().size();
        long rejected = rejectedRowNumbers(job).size();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("rows", rows);
        counts.put("valid", Math.max(0, rows - rejected));
        counts.put("rejected", rejected);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("jobId", job.getIngestionJobId());
        record.put("templateId", job.getTemplateId());
        record.put("status", job.getStatus() == null ? null : job.getStatus().name());
        record.put("currentStep", job.getCurrentStep() == null ? null : job.getCurrentStep().name());
        record.put("parsedBy", job.getParsedBy());
        record.put("counts", counts);
        record.put("errorMessage", job.getErrorMessage());
        record.put("origin", job.getOrigin());
        return record;
    }

    private List<Map<String, Object>> events(IntegrationFlow flow, String ref) {
        List<IntegrationEvent> selected;
        if (ref.startsWith("events:")) {
            String[] range = ref.substring("events:".length()).split("-");
            long from = Long.parseLong(range[0]);
            long to = Long.parseLong(range[1]);
            selected = events.range(flow.getBankId(), from, to, PageRequest.of(0, 10_000));
        } else {
            long id = parseId(ref, "event:");
            selected = events.findByIdAndBankId(id, flow.getBankId()).map(List::of)
                    .orElseThrow(() -> new PayloadRefusedException("Event " + id + " not found."));
        }
        enforceCaps(flow, selected.size());
        List<Map<String, Object>> records = new ArrayList<>();
        for (IntegrationEvent event : selected) {
            var view = JpaIntegrationEventLog.toEvent(event);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", view.id());
            record.put("type", view.type().name());
            record.put("occurredAt", view.occurredAt().toString());
            record.put("entityType", view.entityType());
            record.put("entityId", view.entityId());
            record.put("data", view.data());
            records.add(record);
        }
        return records;
    }

    private OutboundBatch releasedDocument(IntegrationFlow flow, String ref) {
        int docId = parseId(ref, "doc:");
        DocInstance document = documents.getByIdAndBank(docId, flow.getBankId())
                .filter(doc -> DocInstance.STATUS_RELEASED.equals(doc.getStatus()))
                .orElseThrow(() -> new PayloadRefusedException("Released document " + docId
                        + " not found."));
        boolean pdf = flow.getFormat() == PayloadFormat.PDF && document.getLastGeneratedPdf() != null;
        String stored = pdf ? document.getLastGeneratedPdf() : document.getLastGeneratedDocx();
        if (stored == null) {
            throw new PayloadRefusedException("Document " + docId + " has no generated file.");
        }
        Path root = storage.exportsDir().toAbsolutePath().normalize();
        Path path = root.resolve(stored).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new PayloadRefusedException("The generated file of document " + docId + " is missing.");
        }
        byte[] content;
        try {
            content = Files.readAllBytes(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The generated file could not be read.");
        }
        String fileName = "document-" + docId + (pdf ? ".pdf" : ".docx");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("documentId", docId);
        record.put("title", document.getTitle());
        record.put("fileName", fileName);
        record.put("releasedAt", document.getReleasedAt() == null ? null : document.getReleasedAt().toString());
        return new OutboundBatch(null, PayloadKind.RELEASED_DOCUMENTS,
                pdf ? PayloadFormat.PDF : flow.getFormat(), List.of(record), fileName,
                pdf ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content);
    }

    // ---- helpers --------------------------------------------------------------------

    private void enforceCaps(IntegrationFlow flow, int rows) {
        int maxRows = settings.maxRowsPerRun(flow.getBankId());
        int exportCap = exportGuard.capFor(Role.ADMIN, flow.getBankId());
        if (flow.getRowCap() != null && rows > flow.getRowCap()) {
            throw new PayloadRefusedException("The payload has " + rows + " rows, above the flow's "
                    + "row cap of " + flow.getRowCap() + ".");
        }
        if (rows > maxRows) {
            throw new PayloadRefusedException("The payload has " + rows + " rows, above the bank's "
                    + "maximum of " + maxRows + " rows per run.");
        }
        if (rows > exportCap) {
            throw new PayloadRefusedException("The payload has " + rows + " rows, above the export "
                    + "cap of " + exportCap + " (a flow cannot state an over-cap reason).");
        }
    }

    private TableData mask(IntegrationFlow flow, IngestionJob job, TableData table) {
        if (!flow.isMaskPii() || table.getRows().isEmpty()) {
            return table;
        }
        return masking.maskForExport(job.getTemplateId(), table);
    }

    private static TableData mapped(IngestionJob job) {
        if (job.getMappedRowsJson() == null) {
            throw new PayloadRefusedException("Ingestion job " + job.getIngestionJobId()
                    + " has no mapped rows.");
        }
        try {
            return IntegrationJson.MAPPER.readValue(job.getMappedRowsJson(), TableData.class);
        } catch (Exception e) {
            throw new PayloadRefusedException("The rows of job " + job.getIngestionJobId()
                    + " could not be read.");
        }
    }

    private static List<RowValidationResult> validationResults(IngestionJob job) {
        if (job.getValidationResultJson() == null || job.getValidationResultJson().isBlank()) {
            return List.of();
        }
        try {
            return IntegrationJson.MAPPER.readValue(job.getValidationResultJson(),
                    new TypeReference<List<RowValidationResult>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Set<Integer> rejectedRowNumbers(IngestionJob job) {
        Set<Integer> rows = new HashSet<>();
        for (RowValidationResult result : validationResults(job)) {
            if (result.errorMessage() != null) {
                rows.add(result.row());
            }
        }
        return rows;
    }

    private static TableData table(List<Map<String, Object>> records) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Map<String, String> row = new LinkedHashMap<>();
            record.forEach((key, value) -> {
                columns.add(key);
                row.put(key, value == null ? "" : value instanceof Map || value instanceof List
                        ? IntegrationJson.write(value) : String.valueOf(value));
            });
            rows.add(row);
        }
        if (columns.isEmpty()) {
            columns.add("empty");
        }
        return new TableData(new ArrayList<>(columns), rows);
    }

    private static int parseId(String ref, String prefix) {
        if (ref == null || !ref.startsWith(prefix)) {
            throw new PayloadRefusedException("Unexpected payload reference.");
        }
        try {
            return Integer.parseInt(ref.substring(prefix.length()));
        } catch (NumberFormatException e) {
            throw new NoSuchElementException("Unexpected payload reference.");
        }
    }
}
