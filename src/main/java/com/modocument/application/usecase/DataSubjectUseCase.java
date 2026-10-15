package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.FileStore;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
import com.modocument.doccreation.repo.DocImageAssetJpaRepository;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.UploadedFile;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Hashing;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.extraction.sql.ExtractionProcessedRecordJpaRepository;
import com.modocument.infrastructure.extraction.sql.ExtractionRawRecordJpaRepository;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionProcessedRecordEntity;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionRawRecordEntity;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import com.modocument.infrastructure.persistence.AiResultCacheJpaRepository;
import com.modocument.infrastructure.persistence.ImportDetailsJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.UploadedFileJpaRepository;
import com.modocument.infrastructure.retention.AfterCommitFileDeleter;
import com.modocument.infrastructure.retention.PendingFileDeletes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Data-subject rights (ws14, gate C12, PRIV-016): search / export / erase
 * one subject's data across every store of the CALLER'S bank, keyed by a
 * VALUE the subject is known by (NIC, account number, email, full name).
 *
 * <p>Matching rules (documented in docs/security/retention-and-dsr.md):
 * STRUCTURED values (ingestion row cells, import payload values, extraction
 * field values) match on case-insensitive EQUALITY of the trimmed value —
 * an erasure must not take neighbouring subjects with it. UNSTRUCTURED text
 * (extraction raw bodies, doc-creation paragraphs/captions, file names)
 * matches on case-insensitive CONTAINS — a name inside a paragraph is still
 * that subject's data.
 *
 * <p>Uploaded ORIGINAL files are matched by NAME only, plus every file
 * whose PARSED job rows matched; their binary content is NOT scanned
 * (PDF/XLSX/image payloads are not text-searchable in place — the parsed
 * rows ARE that content, searched above). On erase those files are deleted
 * ENTIRELY with their UploadedFile rows: an original document cannot be
 * row-filtered, and the 30-day retention purge bounds what any erasure must
 * cover anyway.
 *
 * <p>Bank scoping is double-walled: every query filters by the caller's
 * BankId explicitly AND PostgreSQL RLS enforces the same tenant on every
 * row touched.
 */
@Service
public class DataSubjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(DataSubjectUseCase.class);
    private static final int MIN_IDENTIFIER_LENGTH = 3;

    private final CurrentUserProvider currentUser;
    private final IngestionJobJpaRepository jobs;
    private final ImportDetailsJpaRepository importDetails;
    private final ExtractionRawRecordJpaRepository extractionRaw;
    private final ExtractionProcessedRecordJpaRepository extractionProcessed;
    private final UploadedFileJpaRepository uploads;
    private final DocContentBlockJpaRepository docBlocks;
    private final DocImageAssetJpaRepository docAssets;
    /** ws31: the values of PDF-template documents (region key to value). */
    private final DocInstanceJpaRepository docInstances;
    private final FileStore storage;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    /** BUG-12: where a file delete that keeps failing after the commit waits for a retry. */
    private final PendingFileDeletes pendingDeletes;
    /** v1.2.0: cached AI results are copies of extracted rows (cleared on erase). */
    private final AiResultCacheJpaRepository aiResultCache;

    public DataSubjectUseCase(CurrentUserProvider currentUser,
                              IngestionJobJpaRepository jobs,
                              ImportDetailsJpaRepository importDetails,
                              ExtractionRawRecordJpaRepository extractionRaw,
                              ExtractionProcessedRecordJpaRepository extractionProcessed,
                              UploadedFileJpaRepository uploads,
                              DocContentBlockJpaRepository docBlocks,
                              DocImageAssetJpaRepository docAssets,
                              DocInstanceJpaRepository docInstances,
                              FileStore storage,
                              AuditRecorder audit,
                              ObjectMapper objectMapper,
                              PendingFileDeletes pendingDeletes,
                              AiResultCacheJpaRepository aiResultCache) {
        this.currentUser = currentUser;
        this.jobs = jobs;
        this.importDetails = importDetails;
        this.extractionRaw = extractionRaw;
        this.extractionProcessed = extractionProcessed;
        this.uploads = uploads;
        this.docBlocks = docBlocks;
        this.docAssets = docAssets;
        this.docInstances = docInstances;
        this.storage = storage;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.pendingDeletes = pendingDeletes;
        this.aiResultCache = aiResultCache;
    }

    // ------------------------------------------------------------- results

    /** One store's hit summary: references and row counts, never values. */
    public record StoreHits(String store, int matches, List<HitRef> hits) {
    }

    /** A reference into one store (ids/labels only — no subject values). */
    public record HitRef(String ref, String label, int rowCount) {
    }

    public record SearchResult(int totalMatches, List<StoreHits> stores) {
    }

    public record ExportBundle(String fileName, byte[] zipBytes, int totalMatches) {
    }

    public record EraseResult(int totalRemoved, Map<String, Integer> counts) {
    }

    // -------------------------------------------------------------- search

    public SearchResult search(String identifier) {
        Scan scan = scan(requireIdentifier(identifier));
        SearchResult result = new SearchResult(scan.totalMatches(), scan.storeHits());
        audit.record(AuditEventSpec.builder("DSR_SEARCH", AuditOutcome.SUCCESS)
                .entity("DataSubject", scan.subjectRef)
                .newValues(scan.countsWithTotal())
                .build());
        // ws28 read audit: where a data subject appears was shown (deduplicated
        // per actor, subject and hour; gated by audit.readAuditEnabled).
        audit.success(com.modocument.application.audit.AuditActions.SENSITIVE_DATA_VIEWED,
                "DataSubject", scan.subjectRef, null, Map.of("view", "dsr-search"));
        return result;
    }

    // -------------------------------------------------------------- export

    /**
     * The portable copy for the subject: a ZIP with the machine-readable
     * JSON (every matching row in full, per store) and a human-readable
     * plain-text rendering of the same content. Deliberately not a PDF: the
     * doc-creation PDF path needs headless LibreOffice, which is absent in
     * most deployments of this stack; a UTF-8 text report is readable
     * everywhere and carries identical content (documented in
     * docs/security/retention-and-dsr.md).
     */
    public ExportBundle export(String identifier) {
        String subject = requireIdentifier(identifier);
        Scan scan = scan(subject);

        byte[] zip = zip(Map.of(
                "dsr-export.json", exportJson(subject, scan),
                "dsr-export.txt", exportText(subject, scan)));

        audit.record(AuditEventSpec.builder("DSR_EXPORTED", AuditOutcome.SUCCESS)
                .entity("DataSubject", scan.subjectRef)
                .newValues(scan.countsWithTotal())
                .build());
        return new ExportBundle("dsr-export-" + scan.subjectRef + ".zip", zip,
                scan.totalMatches());
    }

    // --------------------------------------------------------------- erase

    /**
     * Removes the subject's rows from every store (details per store in the
     * class comment and runbook), deletes original uploads + export archive
     * copies of matched jobs, and audits DSR_ERASED with per-store counts.
     */
    @Transactional
    public EraseResult erase(String identifier) {
        int bankId = currentUser.requireBankId();
        String subject = requireIdentifier(identifier);
        Scan scan = scan(subject);
        List<String> filesToDelete = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        // Ingestion jobs: rewrite the row arrays without the subject's rows.
        int jobRows = 0;
        int uploadCount = 0;
        for (JobMatch match : scan.jobMatches) {
            IngestionJob job = match.job;
            if (match.parsed != null) {
                job.setParsedTableJson(writeTable(filterRows(match.parsed, subject)));
            }
            if (match.mapped != null) {
                job.setMappedRowsJson(writeTable(filterRows(match.mapped, subject)));
            }
            // Validation results are DERIVED data keyed by row index; after
            // rows are removed the indexes are stale, so the checkpoint is
            // cleared — a revalidate regenerates it from the surviving rows.
            job.setValidationResultJson(null);
            job.setUpdatedDate(LocalDateTime.now());
            jobRows += match.rowCount();

            // The ORIGINAL upload contained the subject: file + row go.
            if (job.getFileName() != null) {
                var uploadRow = uploads.findByStoredNameAndBankId(job.getFileName(), bankId);
                if (uploadRow.isPresent()) {
                    uploads.delete(uploadRow.get());
                    addUploadKey(filesToDelete, uploadRow.get().getStoredName());
                    uploadCount++;
                }
            }
            // So do the export archive copies — they hold the exported rows.
            for (FileExportService.Format format : FileExportService.Format.values()) {
                String copy = FileStoreKeys.exportCopy(job.getOwnerUserId(), job.getIngestionJobId(),
                        format.extension());
                if (storedFileExists(copy)) {
                    filesToDelete.add(copy);
                }
            }
        }
        jobs.saveAll(scan.jobMatches.stream().map(m -> m.job).toList());
        counts.put("ingestionJobRows", jobRows);

        // Import details: null the payload copies that named the subject.
        for (ImportDetails details : scan.importDetailMatches) {
            details.setRawJson(null);
        }
        importDetails.saveAll(scan.importDetailMatches);
        counts.put("importDetailRows", scan.importDetailMatches.size());

        // Extraction records: delete outright (each record IS one capture).
        extractionRaw.deleteAll(scan.rawMatches);
        counts.put("extractionRawRecords", scan.rawMatches.size());
        extractionProcessed.deleteAll(scan.processedMatches);
        counts.put("extractionProcessedRecords", scan.processedMatches.size());

        // Uploads matched by NAME (job-matched uploads were handled above).
        for (UploadedFile upload : scan.nameMatchedUploads) {
            if (uploads.existsById(upload.getUploadedFileId())) {
                uploads.delete(upload);
                addUploadKey(filesToDelete, upload.getStoredName());
                uploadCount++;
            }
        }
        counts.put("uploadedFiles", uploadCount);

        // Doc content: the matching blocks go; an image block's asset (file
        // + row) goes with its caption — the caption named the subject.
        for (DocContentBlock block : scan.docBlockMatches) {
            if (block.getAssetId() != null) {
                docAssets.findById(block.getAssetId()).ifPresent(asset -> {
                    addUploadKey(filesToDelete, asset.getFileName());
                    docAssets.delete(asset);
                });
            }
            docBlocks.delete(block);
        }
        counts.put("docContentBlocks", scan.docBlockMatches.size());

        // Document values (ws31): the matching values go; the generated PDF
        // carries them too, so it goes and the next download regenerates.
        int valueCount = 0;
        for (DocValueMatch match : scan.docValueMatches) {
            DocInstance instance = match.instance();
            Map<String, String> kept = new LinkedHashMap<>(readValues(instance.getValuesJson()));
            match.keys().forEach(kept::remove);
            instance.setValuesJson(kept.isEmpty() ? null : writeValues(kept));
            if (instance.getGeneratedPdfKey() != null) {
                filesToDelete.add(instance.getGeneratedPdfKey());
                instance.setGeneratedPdfKey(null);
                instance.setLastGeneratedPdf(null);
            }
            instance.setUpdatedDate(LocalDateTime.now());
            docInstances.save(instance);
            valueCount += match.keys().size();
        }
        counts.put("docValues", valueCount);

        // The AI result cache holds copies of extracted rows under a text
        // hash, so the subject's values cannot be found in it reliably: the
        // bank's whole cache goes (it only saves model calls). Not counted in
        // the subject's total.
        int aiCacheCleared = aiResultCache.purgeBank(bankId);

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("total", total);
        evidence.putAll(counts);
        evidence.put("aiResultCacheCleared", aiCacheCleared);
        audit.record(AuditEventSpec.builder("DSR_ERASED", AuditOutcome.SUCCESS)
                .entity("DataSubject", scan.subjectRef)
                .newValues(evidence)
                .build());

        AfterCommitFileDeleter.deleteAfterCommit(storage, filesToDelete, bankId, pendingDeletes);
        log.info("DSR erase removed {} item(s) across {} store(s) for subject ref {}",
                total, counts.size(), scan.subjectRef);
        return new EraseResult(total, counts);
    }

    // ---------------------------------------------------------------- scan

    private static final class JobMatch {
        final IngestionJob job;
        final TableData parsed;
        final TableData mapped;
        final int parsedRowMatches;
        final int mappedRowMatches;

        JobMatch(IngestionJob job, TableData parsed, TableData mapped,
                 int parsedRowMatches, int mappedRowMatches) {
            this.job = job;
            this.parsed = parsed;
            this.mapped = mapped;
            this.parsedRowMatches = parsedRowMatches;
            this.mappedRowMatches = mappedRowMatches;
        }

        int rowCount() {
            return parsedRowMatches + mappedRowMatches;
        }
    }

    private final class Scan {
        final String subjectRef;
        final List<JobMatch> jobMatches = new ArrayList<>();
        final List<ImportDetails> importDetailMatches = new ArrayList<>();
        final List<ExtractionRawRecordEntity> rawMatches = new ArrayList<>();
        final List<ExtractionProcessedRecordEntity> processedMatches = new ArrayList<>();
        final List<UploadedFile> nameMatchedUploads = new ArrayList<>();
        final Set<String> jobMatchedStoredNames = new LinkedHashSet<>();
        final List<DocContentBlock> docBlockMatches = new ArrayList<>();
        final List<DocValueMatch> docValueMatches = new ArrayList<>();

        Scan(String subject) {
            this.subjectRef = Hashing.sha256(subject.toLowerCase(Locale.ROOT)).substring(0, 16);
        }

        int totalMatches() {
            return jobMatches.stream().mapToInt(JobMatch::rowCount).sum()
                    + importDetailMatches.size() + rawMatches.size() + processedMatches.size()
                    + nameMatchedUploads.size() + jobMatchedStoredNames.size()
                    + docBlockMatches.size()
                    + docValueMatches.stream().mapToInt(m -> m.keys().size()).sum();
        }

        List<StoreHits> storeHits() {
            List<StoreHits> stores = new ArrayList<>();
            stores.add(new StoreHits("ingestionJobs",
                    jobMatches.stream().mapToInt(JobMatch::rowCount).sum(),
                    jobMatches.stream().map(m -> new HitRef(
                            String.valueOf(m.job.getIngestionJobId()),
                            m.job.getFileName(), m.rowCount())).toList()));
            stores.add(new StoreHits("importDetails", importDetailMatches.size(),
                    importDetailMatches.stream().map(d -> new HitRef(
                            String.valueOf(d.getImportDetailsId()),
                            "import " + d.getImportHistoryId(), 1)).toList()));
            stores.add(new StoreHits("extractionRawRecords", rawMatches.size(),
                    rawMatches.stream().map(r -> new HitRef(String.valueOf(r.getId()),
                            r.getUri(), 1)).toList()));
            stores.add(new StoreHits("extractionProcessedRecords", processedMatches.size(),
                    processedMatches.stream().map(r -> new HitRef(String.valueOf(r.getId()),
                            "job " + r.getJobId(), 1)).toList()));
            List<HitRef> uploadRefs = new ArrayList<>();
            nameMatchedUploads.forEach(u -> uploadRefs.add(
                    new HitRef(u.getStoredName(), "name match", 1)));
            jobMatchedStoredNames.forEach(name -> uploadRefs.add(
                    new HitRef(name, "parsed rows match", 1)));
            stores.add(new StoreHits("uploadedFiles", uploadRefs.size(), uploadRefs));
            stores.add(new StoreHits("docContentBlocks", docBlockMatches.size(),
                    docBlockMatches.stream().map(b -> new HitRef(
                            String.valueOf(b.getDocContentBlockId()),
                            "document " + b.getInstanceId(), 1)).toList()));
            stores.add(new StoreHits("docValues",
                    docValueMatches.stream().mapToInt(m -> m.keys().size()).sum(),
                    docValueMatches.stream().map(m -> new HitRef(
                            String.valueOf(m.instance().getDocInstanceId()),
                            "document " + m.instance().getDocInstanceId(), m.keys().size())).toList()));
            return stores;
        }

        /** Counts-only payload for the audit trail — never subject values. */
        Map<String, Object> countsWithTotal() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("total", totalMatches());
            storeHits().forEach(store -> counts.put(store.store(), store.matches()));
            return counts;
        }
    }

    private Scan scan(String subject) {
        int bankId = currentUser.requireBankId();
        Scan scan = new Scan(subject);

        for (IngestionJob job : jobs.findByBankId(bankId)) {
            TableData parsed = readTable(job.getParsedTableJson());
            TableData mapped = readTable(job.getMappedRowsJson());
            int parsedHits = countMatchingRows(parsed, subject);
            int mappedHits = countMatchingRows(mapped, subject);
            if (parsedHits > 0 || mappedHits > 0) {
                scan.jobMatches.add(new JobMatch(job, parsed, mapped, parsedHits, mappedHits));
                if (job.getFileName() != null) {
                    scan.jobMatchedStoredNames.add(job.getFileName());
                }
            }
        }

        for (ImportDetails details : importDetails.findByBankIdAndRawJsonIsNotNull(bankId)) {
            if (jsonHoldsValue(details.getRawJson(), subject)) {
                scan.importDetailMatches.add(details);
            }
        }

        for (ExtractionRawRecordEntity record : extractionRaw.findByBankId(bankId)) {
            if (containsIgnoreCase(record.getBody(), subject)) {
                scan.rawMatches.add(record);
            }
        }
        for (ExtractionProcessedRecordEntity record : extractionProcessed.findByBankId(bankId)) {
            if (jsonHoldsValue(record.getFieldsJson(), subject)) {
                scan.processedMatches.add(record);
            }
        }

        for (UploadedFile upload : uploads.findByBankId(bankId)) {
            if (containsIgnoreCase(upload.getOriginalName(), subject)
                    || containsIgnoreCase(upload.getStoredName(), subject)) {
                if (!scan.jobMatchedStoredNames.contains(upload.getStoredName())) {
                    scan.nameMatchedUploads.add(upload);
                }
            }
        }

        for (DocContentBlock block : docBlocks.findByBankId(bankId)) {
            if (containsIgnoreCase(block.getTextContent(), subject)
                    || containsIgnoreCase(block.getCaption(), subject)) {
                scan.docBlockMatches.add(block);
            }
        }

        for (DocInstance instance : docInstances.findByBankIdAndValuesJsonIsNotNull(bankId)) {
            List<String> keys = new ArrayList<>();
            readValues(instance.getValuesJson()).forEach((key, value) -> {
                if (containsIgnoreCase(value, subject)) {
                    keys.add(key);
                }
            });
            if (!keys.isEmpty()) {
                scan.docValueMatches.add(new DocValueMatch(instance, keys));
            }
        }
        return scan;
    }

    // ------------------------------------------------------------ matching

    private static String requireIdentifier(String identifier) {
        String trimmed = identifier == null ? "" : identifier.trim();
        if (trimmed.length() < MIN_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("The subject identifier must be at least "
                    + MIN_IDENTIFIER_LENGTH + " characters.");
        }
        return trimmed;
    }

    /** A PDF-template document whose values (by key) name the subject. */
    private record DocValueMatch(DocInstance instance, List<String> keys) {
    }

    private Map<String, String> readValues(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            objectMapper.readTree(json).fields().forEachRemaining(field ->
                    values.put(field.getKey(), field.getValue().isNull() ? null : field.getValue().asText()));
            return values;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private String writeValues(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the document values.", e);
        }
    }

    private static boolean valueMatches(String value, String subject) {
        return value != null && value.trim().equalsIgnoreCase(subject);
    }

    private static boolean containsIgnoreCase(String text, String subject) {
        return text != null
                && text.toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT));
    }

    private static boolean rowMatches(Map<String, String> row, String subject) {
        return row != null && row.values().stream().anyMatch(v -> valueMatches(v, subject));
    }

    private static int countMatchingRows(TableData table, String subject) {
        if (table == null || table.getRows() == null) {
            return 0;
        }
        return (int) table.getRows().stream().filter(row -> rowMatches(row, subject)).count();
    }

    private static TableData filterRows(TableData table, String subject) {
        List<Map<String, String>> kept = table.getRows() == null ? List.of()
                : table.getRows().stream().filter(row -> !rowMatches(row, subject)).toList();
        return new TableData(table.getColumns(), new ArrayList<>(kept));
    }

    private static List<Map<String, String>> matchingRows(TableData table, String subject) {
        if (table == null || table.getRows() == null) {
            return List.of();
        }
        return table.getRows().stream().filter(row -> rowMatches(row, subject)).toList();
    }

    /** Any textual leaf of the JSON equals the subject (trimmed, case-insensitive). */
    private boolean jsonHoldsValue(String json, String subject) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            return anyTextLeafMatches(objectMapper.readTree(json), subject);
        } catch (IOException unparseable) {
            // A payload we cannot parse still gets a defensive text scan.
            return containsIgnoreCase(json, subject);
        }
    }

    private static boolean anyTextLeafMatches(JsonNode node, String subject) {
        if (node == null) {
            return false;
        }
        if (node.isTextual()) {
            return valueMatches(node.asText(), subject);
        }
        for (JsonNode child : node) {
            if (anyTextLeafMatches(child, subject)) {
                return true;
            }
        }
        return false;
    }

    private TableData readTable(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TableData.class);
        } catch (IOException e) {
            log.warn("DSR scan could not parse a table checkpoint: {}", e.getMessage());
            return null;
        }
    }

    private String writeTable(TableData table) {
        try {
            return objectMapper.writeValueAsString(table);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rewrite table checkpoint.", e);
        }
    }

    /**
     * The upload's store key; a legacy stored name the store cannot address
     * (FileStoreKeys) is logged and skipped so the rest of the erase — rows
     * included — still completes.
     */
    private static void addUploadKey(List<String> filesToDelete, String storedName) {
        FileStoreKeys.tryUpload(storedName).ifPresentOrElse(filesToDelete::add,
                () -> log.warn("DSR erase could not address stored file '{}'; its row was removed, "
                        + "remove the file by hand", storedName));
    }

    private boolean storedFileExists(String key) {
        try {
            return storage.exists(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not check stored file " + key, e);
        }
    }

    // ------------------------------------------------------------ exporting

    private byte[] exportJson(String subject, Scan scan) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("identifier", subject);
        root.put("generatedAt", Instant.now().toString());
        root.put("totalMatches", scan.totalMatches());
        ObjectNode stores = root.putObject("stores");

        ArrayNode jobsNode = stores.putArray("ingestionJobs");
        for (JobMatch match : scan.jobMatches) {
            ObjectNode jobNode = jobsNode.addObject();
            jobNode.put("jobId", match.job.getIngestionJobId());
            jobNode.put("fileName", match.job.getFileName());
            jobNode.set("parsedRows", objectMapper.valueToTree(matchingRows(match.parsed, subject)));
            jobNode.set("mappedRows", objectMapper.valueToTree(matchingRows(match.mapped, subject)));
        }

        ArrayNode detailsNode = stores.putArray("importDetails");
        for (ImportDetails details : scan.importDetailMatches) {
            ObjectNode node = detailsNode.addObject();
            node.put("importDetailsId", details.getImportDetailsId());
            node.put("importHistoryId", details.getImportHistoryId());
            node.set("payload", subjectView(readTree(details.getRawJson()), subject));
        }

        ArrayNode rawNode = stores.putArray("extractionRawRecords");
        for (ExtractionRawRecordEntity record : scan.rawMatches) {
            ObjectNode node = rawNode.addObject();
            node.put("recordId", record.getId());
            node.put("jobId", record.getJobId());
            node.put("uri", record.getUri());
            node.put("fetchedAt", record.getFetchedAt() == null
                    ? null : record.getFetchedAt().toString());
            node.put("body", record.getBody());
        }

        ArrayNode processedNode = stores.putArray("extractionProcessedRecords");
        for (ExtractionProcessedRecordEntity record : scan.processedMatches) {
            ObjectNode node = processedNode.addObject();
            node.put("recordId", record.getId());
            node.put("jobId", record.getJobId());
            node.put("processedAt", record.getProcessedAt() == null
                    ? null : record.getProcessedAt().toString());
            node.set("fields", readTree(record.getFieldsJson()));
        }

        ArrayNode uploadsNode = stores.putArray("uploadedFiles");
        for (UploadedFile upload : scan.nameMatchedUploads) {
            addUpload(uploadsNode, upload.getStoredName(), upload.getOriginalName(),
                    "file name matches the identifier");
        }
        for (String storedName : scan.jobMatchedStoredNames) {
            addUpload(uploadsNode, storedName, null,
                    "original upload of a job whose parsed rows match");
        }

        ArrayNode blocksNode = stores.putArray("docContentBlocks");
        for (DocContentBlock block : scan.docBlockMatches) {
            ObjectNode node = blocksNode.addObject();
            node.put("blockId", block.getDocContentBlockId());
            node.put("instanceId", block.getInstanceId());
            node.put("textContent", block.getTextContent());
            node.put("caption", block.getCaption());
        }

        ArrayNode valuesNode = stores.putArray("docValues");
        for (DocValueMatch match : scan.docValueMatches) {
            ObjectNode node = valuesNode.addObject();
            node.put("instanceId", match.instance().getDocInstanceId());
            ObjectNode fields = node.putObject("values");
            Map<String, String> values = readValues(match.instance().getValuesJson());
            match.keys().forEach(key -> fields.put(key, values.get(key)));
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize the DSR export.", e);
        }
    }

    private static void addUpload(ArrayNode uploadsNode, String storedName, String originalName,
                                  String reason) {
        ObjectNode node = uploadsNode.addObject();
        node.put("storedName", storedName);
        node.put("originalName", originalName);
        node.put("note", reason + "; the stored binary is listed, not embedded — "
                + "its parsed content is in ingestionJobs above");
    }

    private JsonNode readTree(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (IOException e) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }

    /**
     * The subject's view of an arbitrary JSON payload: arrays of OBJECTS are
     * pruned to the elements that carry the subject's value (recursively),
     * so an export never hands one subject another subject's rows. Scalar
     * arrays (column lists and other metadata) and plain fields pass
     * through unchanged.
     */
    private JsonNode subjectView(JsonNode node, String subject) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            boolean containerElements = false;
            for (JsonNode element : node) {
                if (element.isContainerNode()) {
                    containerElements = true;
                    break;
                }
            }
            if (!containerElements) {
                return node;
            }
            ArrayNode filtered = objectMapper.createArrayNode();
            for (JsonNode element : node) {
                if (anyTextLeafMatches(element, subject)) {
                    filtered.add(subjectView(element, subject));
                }
            }
            return filtered;
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            node.properties().forEach(entry ->
                    copy.set(entry.getKey(), subjectView(entry.getValue(), subject)));
            return copy;
        }
        return node;
    }

    /** The human-readable twin of the JSON: same content, plain UTF-8 text. */
    private byte[] exportText(String subject, Scan scan) {
        StringBuilder text = new StringBuilder();
        text.append("DATA SUBJECT ACCESS EXPORT\n");
        text.append("==========================\n");
        text.append("Identifier: ").append(subject).append('\n');
        text.append("Generated:  ").append(Instant.now()).append('\n');
        text.append("Matches:    ").append(scan.totalMatches()).append('\n');

        text.append("\n-- Ingestion jobs (uploaded tabular data) --\n");
        for (JobMatch match : scan.jobMatches) {
            text.append("Job ").append(match.job.getIngestionJobId())
                    .append(" (file ").append(match.job.getFileName()).append(")\n");
            for (Map<String, String> row : matchingRows(match.parsed, subject)) {
                text.append("  parsed row: ").append(row).append('\n');
            }
            for (Map<String, String> row : matchingRows(match.mapped, subject)) {
                text.append("  mapped row: ").append(row).append('\n');
            }
        }

        text.append("\n-- Import step payloads --\n");
        for (ImportDetails details : scan.importDetailMatches) {
            text.append("Import detail ").append(details.getImportDetailsId())
                    .append(" (import ").append(details.getImportHistoryId()).append("): ")
                    .append(subjectView(readTree(details.getRawJson()), subject)).append('\n');
        }

        text.append("\n-- Extraction raw captures --\n");
        for (ExtractionRawRecordEntity record : scan.rawMatches) {
            text.append("Record ").append(record.getId()).append(" from ")
                    .append(record.getUri()).append(":\n")
                    .append(record.getBody()).append('\n');
        }

        text.append("\n-- Extraction processed records --\n");
        for (ExtractionProcessedRecordEntity record : scan.processedMatches) {
            text.append("Record ").append(record.getId()).append(": ")
                    .append(record.getFieldsJson()).append('\n');
        }

        text.append("\n-- Uploaded files --\n");
        for (UploadedFile upload : scan.nameMatchedUploads) {
            text.append(upload.getStoredName()).append(" (name match)\n");
        }
        for (String storedName : scan.jobMatchedStoredNames) {
            text.append(storedName).append(" (original upload of a matching job)\n");
        }

        text.append("\n-- Document content --\n");
        for (DocContentBlock block : scan.docBlockMatches) {
            text.append("Document ").append(block.getInstanceId())
                    .append(", block ").append(block.getDocContentBlockId()).append(": ")
                    .append(block.getTextContent() == null
                            ? block.getCaption() : block.getTextContent())
                    .append('\n');
        }
        for (DocValueMatch match : scan.docValueMatches) {
            Map<String, String> values = readValues(match.instance().getValuesJson());
            for (String key : match.keys()) {
                text.append("Document ").append(match.instance().getDocInstanceId())
                        .append(", field ").append(key).append(": ").append(values.get(key)).append('\n');
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not assemble the DSR export ZIP.", e);
        }
        return bytes.toByteArray();
    }
}
