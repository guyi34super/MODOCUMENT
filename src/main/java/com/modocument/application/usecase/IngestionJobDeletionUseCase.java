package com.modocument.application.usecase;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.UploadedFileRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.infrastructure.export.FileExportService;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.retention.AfterCommitFileDeleter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Owner-initiated deletion of an ingestion job (ws14, gate C5): the user who
 * uploaded data can remove it ahead of the 30-day retention purge. Deletes
 * the job ROW (not just the checkpoints), its export archive copies
 * (exports/u{owner}/job{id}.{ext}) and — when no OTHER job of the owner
 * still references the same stored name — the uploaded file plus its
 * UploadedFile row. Audited as JOB_DELETED.
 *
 * <p>Owner-scoped like every job read: a job that is not the caller's 404s
 * identically to a non-existent id.
 */
@Service
public class IngestionJobDeletionUseCase {

    private final IngestionJobRepository jobs;
    private final UploadedFileRepository uploads;
    private final CurrentUserProvider currentUser;
    private final UploadStorage uploadStorage;
    private final StorageProperties storage;
    private final AuditRecorder audit;

    public IngestionJobDeletionUseCase(IngestionJobRepository jobs,
                                       UploadedFileRepository uploads,
                                       CurrentUserProvider currentUser,
                                       UploadStorage uploadStorage,
                                       StorageProperties storage,
                                       AuditRecorder audit) {
        this.jobs = jobs;
        this.uploads = uploads;
        this.currentUser = currentUser;
        this.uploadStorage = uploadStorage;
        this.storage = storage;
        this.audit = audit;
    }

    @Transactional
    public void delete(int jobId) {
        int userId = currentUser.requireUserId();
        IngestionJob job = jobs.getByIdAndOwner(jobId, userId).orElseThrow(
                () -> new NoSuchElementException("Ingestion job " + jobId + " not found."));

        List<Path> filesToDelete = new ArrayList<>();
        Path ownerDir = storage.exportsDir().resolve("u" + userId);
        for (FileExportService.Format format : FileExportService.Format.values()) {
            Path copy = ownerDir.resolve("job" + jobId + format.extension());
            if (Files.exists(copy)) {
                filesToDelete.add(copy);
            }
        }

        boolean uploadDeleted = false;
        String fileName = job.getFileName();
        // The stored upload may back several jobs (create-from-existing):
        // only the LAST job referencing it takes the file and its row along.
        if (fileName != null && jobs.countByFileNameAndOwner(fileName, userId) <= 1) {
            var uploadRow = uploads.getByStoredNameAndOwner(fileName, userId);
            if (uploadRow.isPresent()) {
                uploads.delete(uploadRow.get());
                filesToDelete.add(uploadStorage.resolve(fileName));
                uploadDeleted = true;
            }
        }

        jobs.delete(job);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fileName", fileName);
        snapshot.put("currentStep", job.getCurrentStep());
        snapshot.put("status", job.getStatus());
        snapshot.put("uploadDeleted", uploadDeleted);
        snapshot.put("archiveCopiesDeleted", filesToDelete.size() - (uploadDeleted ? 1 : 0));
        audit.success("JOB_DELETED", "IngestionJob", String.valueOf(jobId), snapshot, null);

        AfterCommitFileDeleter.deleteAfterCommit(filesToDelete);
    }
}
