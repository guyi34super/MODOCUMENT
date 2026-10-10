package com.modocument.infrastructure.retention;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
import com.modocument.doccreation.repo.DocImageAssetJpaRepository;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.UploadedFile;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.extraction.sql.ExtractionProcessedRecordJpaRepository;
import com.modocument.infrastructure.extraction.sql.ExtractionRawRecordJpaRepository;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.persistence.ImportDetailsJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.UploadedFileJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-bank worker of the 30-day retention purge (ws14, gate C5,
 * PRIV-013). One bank's purge is ONE transaction: the row changes and the
 * RETENTION_PURGE_COMPLETED audit event (SUCCESS — it joins the
 * transaction, so no purge commits unaudited) stand or fall together, and
 * file deletes only hit the disk after the commit
 * ({@link AfterCommitFileDeleter}).
 *
 * <p>What each category does — the full schedule with rationale lives in
 * docs/security/retention-and-dsr.md:
 * <ul>
 *   <li><b>Uploaded files</b> — file on disk AND UploadedFile row deleted
 *       {@code UploadedAt < cutoff} (even when a younger job still points at
 *       the stored name: the policy is 30 days from upload, and the job
 *       keeps its own checkpoints until its own cutoff).</li>
 *   <li><b>Ingestion jobs</b> — the customer-data checkpoint columns
 *       (ParsedTableJson, MappedRowsJson, ValidationResultJson) are nulled
 *       and PurgedAt set; the row survives as an empty report skeleton.
 *       MappingJson/SuggestedMappingJson stay: they map COLUMN NAMES to
 *       template fields (configuration that feeds learned mappings), not
 *       customer rows. ExportInfoJson stays: file name/format/row count,
 *       no values. The export ARCHIVE copies
 *       (exports/u{owner}/job{id}.{ext}) are deleted — they hold the actual
 *       rows.</li>
 *   <li><b>Import details</b> — RawJson (checkpoint payload copies) nulled
 *       where the owning import is past the cutoff; row skeleton stays.</li>
 *   <li><b>Extraction records</b> — raw Body and processed FieldsJson
 *       nulled past the cutoff; fetch/dedup metadata stays.</li>
 *   <li><b>Doc-creation instances</b> — customer content (content blocks,
 *       image assets + their files, generated docx/pdf) deleted 30 days
 *       after the LAST UPDATE, ContentPurgedAt set, the instance shell
 *       (title, status, dates) stays. Blueprints/sections are configuration
 *       and are never purged.</li>
 * </ul>
 */
@Service
public class RetentionPurger {

    private final UploadedFileJpaRepository uploadedFiles;
    private final IngestionJobJpaRepository ingestionJobs;
    private final ImportDetailsJpaRepository importDetails;
    private final ExtractionRawRecordJpaRepository extractionRaw;
    private final ExtractionProcessedRecordJpaRepository extractionProcessed;
    private final DocInstanceJpaRepository docInstances;
    private final DocContentBlockJpaRepository docBlocks;
    private final DocImageAssetJpaRepository docAssets;
    private final StorageProperties storage;
    private final AuditRecorder audit;

    public RetentionPurger(UploadedFileJpaRepository uploadedFiles,
                           IngestionJobJpaRepository ingestionJobs,
                           ImportDetailsJpaRepository importDetails,
                           ExtractionRawRecordJpaRepository extractionRaw,
                           ExtractionProcessedRecordJpaRepository extractionProcessed,
                           DocInstanceJpaRepository docInstances,
                           DocContentBlockJpaRepository docBlocks,
                           DocImageAssetJpaRepository docAssets,
                           StorageProperties storage,
                           AuditRecorder audit) {
        this.uploadedFiles = uploadedFiles;
        this.ingestionJobs = ingestionJobs;
        this.importDetails = importDetails;
        this.extractionRaw = extractionRaw;
        this.extractionProcessed = extractionProcessed;
        this.docInstances = docInstances;
        this.docBlocks = docBlocks;
        this.docAssets = docAssets;
        this.storage = storage;
        this.audit = audit;
    }

    /**
     * Purges one bank's customer data older than the cutoff. Must run with
     * this bank's tenant context bound (the job wraps each call in
     * {@code TenantContext.runAs(bankId, ...)}), so PostgreSQL RLS matches
     * the explicit BankId filters below.
     */
    @Transactional
    public BankPurgeSummary purgeBank(int bankId, LocalDateTime cutoff, Instant cutoffInstant,
                                      int retentionDays) {
        List<Path> filesToDelete = new ArrayList<>();

        // Uploaded files: disk file + ownership row.
        List<UploadedFile> oldUploads = uploadedFiles.findByBankIdAndUploadedAtBefore(bankId, cutoff);
        for (UploadedFile upload : oldUploads) {
            filesToDelete.add(resolveWithin(storage.uploadsDir(), upload.getStoredName()));
        }
        uploadedFiles.deleteAll(oldUploads);

        // Ingestion jobs: null the customer-row checkpoints, keep the skeleton.
        int archiveCount = 0;
        List<IngestionJob> oldJobs =
                ingestionJobs.findByBankIdAndUpdatedDateBeforeAndPurgedAtIsNull(bankId, cutoff);
        for (IngestionJob job : oldJobs) {
            job.setParsedTableJson(null);
            job.setMappedRowsJson(null);
            job.setValidationResultJson(null);
            job.setPurgedAt(LocalDateTime.now());
            archiveCount += collectArchiveCopies(job, filesToDelete);
        }
        ingestionJobs.saveAll(oldJobs);

        int importRows = importDetails.purgeRawJsonOlderThan(bankId, cutoff);
        int rawBodies = extractionRaw.purgeBodiesOlderThan(bankId, cutoffInstant);
        int processedFields = extractionProcessed.purgeFieldsOlderThan(bankId, cutoffInstant);

        // Doc-creation content: blocks + assets (+files) + generated exports.
        int blockCount = 0;
        int assetCount = 0;
        List<DocInstance> oldInstances =
                docInstances.findByBankIdAndUpdatedDateBeforeAndContentPurgedAtIsNull(bankId, cutoff);
        for (DocInstance instance : oldInstances) {
            int id = instance.getDocInstanceId();
            blockCount += docBlocks.findByInstanceIdOrderBySectionIdAscOrderIndexAsc(id).size();
            List<DocImageAsset> assetList = docAssets.findByInstanceIdOrderByDocImageAssetId(id);
            assetCount += assetList.size();
            for (DocImageAsset asset : assetList) {
                filesToDelete.add(resolveWithin(storage.uploadsDir(), asset.getFileName()));
            }
            docBlocks.deleteByInstanceId(id);
            docAssets.deleteByInstanceId(id);
            if (instance.getLastGeneratedDocx() != null) {
                filesToDelete.add(resolveWithin(storage.exportsDir(), instance.getLastGeneratedDocx()));
                instance.setLastGeneratedDocx(null);
            }
            if (instance.getLastGeneratedPdf() != null) {
                filesToDelete.add(resolveWithin(storage.exportsDir(), instance.getLastGeneratedPdf()));
                instance.setLastGeneratedPdf(null);
            }
            instance.setContentPurgedAt(LocalDateTime.now());
        }
        docInstances.saveAll(oldInstances);

        BankPurgeSummary summary = new BankPurgeSummary(bankId, oldUploads.size(), oldJobs.size(),
                archiveCount, importRows, rawBodies, processedFields,
                oldInstances.size(), blockCount, assetCount);

        // The gate C5 evidence: counts per category, one event per bank per
        // run (zero counts included — proof the bank WAS swept).
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("retentionDays", retentionDays);
        evidence.put("cutoff", cutoff.toString());
        evidence.putAll(summary.counts());
        audit.record(AuditEventSpec.builder("RETENTION_PURGE_COMPLETED", AuditOutcome.SUCCESS)
                .entity("Bank", String.valueOf(bankId))
                .bankId(bankId)
                .newValues(evidence)
                .build());

        AfterCommitFileDeleter.deleteAfterCommit(filesToDelete);
        return summary;
    }

    /**
     * The export archive copies IngestionExportController keeps under
     * exports/u{owner}/job{id}.{ext} — one per format, overwritten per
     * export. Counts only the copies that actually exist right now.
     */
    private int collectArchiveCopies(IngestionJob job, List<Path> filesToDelete) {
        int found = 0;
        Path ownerDir = storage.exportsDir().resolve("u" + job.getOwnerUserId());
        for (FileExportService.Format format : FileExportService.Format.values()) {
            Path copy = ownerDir.resolve("job" + job.getIngestionJobId() + format.extension());
            if (Files.exists(copy)) {
                filesToDelete.add(copy);
                found++;
            }
        }
        return found;
    }

    /** Resolves a stored name inside a managed directory, refusing traversal. */
    static Path resolveWithin(Path dir, String name) {
        Path candidate = dir.resolve(name).normalize();
        if (!candidate.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid stored file name: " + name);
        }
        return candidate;
    }
}
