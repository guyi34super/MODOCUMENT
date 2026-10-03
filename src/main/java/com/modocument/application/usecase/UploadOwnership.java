package com.modocument.application.usecase;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.UploadedFileRepository;
import com.modocument.domain.entity.UploadedFile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    public UploadOwnership(UploadedFileRepository uploads, CurrentUserProvider currentUser) {
        this.uploads = uploads;
        this.currentUser = currentUser;
    }

    /** Records the caller as owner of a file just stored by UploadStorage. */
    public UploadedFile record(String storedName, String originalName, long sizeBytes) {
        UploadedFile entity = new UploadedFile();
        entity.setStoredName(storedName);
        entity.setOriginalName(originalName);
        entity.setOwnerUserId(currentUser.requireUserId());
        entity.setBankId(currentUser.requireBankId());
        entity.setSizeBytes(sizeBytes);
        entity.setUploadedAt(LocalDateTime.now(ZoneOffset.UTC));
        return uploads.add(entity);
    }

    /**
     * Verifies the current user owns the stored file.
     *
     * @throws NoSuchElementException when no row exists or it is not the
     *     caller's — surfaced as 404.
     */
    public void require(String storedName) {
        uploads.getByStoredNameAndOwner(storedName, currentUser.requireUserId()).orElseThrow(
                () -> new NoSuchElementException("File '" + storedName + "' not found."));
    }
}
