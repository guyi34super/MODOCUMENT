package com.modocument.application.port;

import com.modocument.domain.entity.UploadedFile;

import java.util.Optional;

/** Ownership records for files in the shared uploads/ folder (V20). */
public interface UploadedFileRepository {

    UploadedFile add(UploadedFile entity);

    /** Owner-scoped lookup: empty for a file the caller does not own. */
    Optional<UploadedFile> getByStoredNameAndOwner(String storedName, int ownerUserId);

    /**
     * Duplicate probe (V31): an earlier upload by the same owner with the same
     * content hash under the same original name, if any.
     */
    Optional<UploadedFile> getDuplicate(int ownerUserId, String sha256Hex, String originalName);

    /** Removes the ownership row (ws14 job deletion / DSR erase / retention). */
    void delete(UploadedFile entity);
}
