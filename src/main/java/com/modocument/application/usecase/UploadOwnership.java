package com.modocument.application.usecase;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.UploadedFileRepository;
import com.modocument.domain.entity.UploadedFile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Shared authorization guard for the uploads/ folder, mirroring
 * {@link TemplateOwnership}: a stored file belongs to whoever uploaded it, so
 * every endpoint that accepts a {@code fileName} referencing an existing
 * upload resolves it here before touching the bytes.
 *
 * <p>A stored name without an {@code UploadedFile} row — another user's file,
 * a guessed name, or a pre-V20 upload — is a {@link NoSuchElementException}
 * (surfaced as 404), so cross-user probing cannot tell "not yours" from
 * "does not exist".
 */
@Service
public class UploadOwnership {

    private final UploadedFileRepository uploads;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;

    public UploadOwnership(UploadedFileRepository uploads, CurrentUserProvider currentUser,
                           AuditRecorder audit) {
        this.uploads = uploads;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** Records the caller as owner of a file just stored by UploadStorage. */
    public UploadedFile record(String storedName, String originalName, long sizeBytes,
                               String sha256Hex) {
        UploadedFile entity = new UploadedFile();
        entity.setStoredName(storedName);
        entity.setOriginalName(originalName);
        entity.setOwnerUserId(currentUser.requireUserId());
        entity.setBankId(currentUser.requireBankId());
        entity.setSizeBytes(sizeBytes);
        entity.setSha256Hex(sha256Hex);
        entity.setUploadedAt(LocalDateTime.now(ZoneOffset.UTC));
        UploadedFile saved = uploads.add(entity);
        // ws28: every stored upload (the plain upload, the ingestion upload
        // and the integration API's POST /files) is audited here, once. The
        // original name stays out: file names can carry personal data.
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storedName", storedName);
        values.put("sizeBytes", sizeBytes);
        values.put("sha256", sha256Hex);
        audit.success(AuditActions.FILE_UPLOADED, "UploadedFile", storedName, null, values);
        return saved;
    }

    /**
     * Duplicate-upload gate (C6): the SAME owner re-uploading the SAME bytes
     * under the SAME original name is a mistake worth stopping before anything
     * is written — the earlier upload already serves every purpose of the new
     * one. The same bytes under a DIFFERENT name stay allowed on purpose: a
     * copy deliberately saved under a new name is a new upload, and template
     * test runs against renamed copies are a real workflow.
     *
     * @throws IllegalStateException surfaced as 409.
     */
    public void rejectDuplicate(String originalName, String sha256Hex) {
        uploads.getDuplicate(currentUser.requireUserId(), sha256Hex, originalName)
                .ifPresent(existing -> {
                    throw new IllegalStateException("You already uploaded '" + originalName
                            + "' with identical content (stored as '" + existing.getStoredName()
                            + "'). Re-use that upload or rename the file if this copy is "
                            + "intentional.");
                });
    }

    /**
     * Verifies the current user owns the stored file.
     *
     * @throws NoSuchElementException when no row exists or it is not the
     *     caller's — surfaced as 404.
     */
    public void require(String storedName) {
        uploads.getByStoredNameAndOwner(storedName, currentUser.requireUserId()).orElseThrow(
                () -> {
                    // ws28: owner-scoped 404 probe, audited ACCESS_DENIED (aggregated).
                    audit.record(AuditActions.notFoundProbe("UploadedFile", storedName));
                    return new NoSuchElementException("File '" + storedName + "' not found.");
                });
    }
}
