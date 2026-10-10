package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadedFileJpaRepository extends JpaRepository<UploadedFile, Integer> {

    Optional<UploadedFile> findByStoredNameAndOwnerUserId(String storedName, int ownerUserId);

    Optional<UploadedFile> findFirstByOwnerUserIdAndSha256HexAndOriginalName(
            int ownerUserId, String sha256Hex, String originalName);

    /** DSR scan (ws14): every upload of one bank. RLS backs this on PostgreSQL. */
    java.util.List<UploadedFile> findByBankId(int bankId);

    /** DSR erase (ws14): the ownership row of a matched job's stored file. */
    Optional<UploadedFile> findByStoredNameAndBankId(String storedName, int bankId);

    /** Retention sweep (ws14): uploads past the cutoff. */
    java.util.List<UploadedFile> findByBankIdAndUploadedAtBefore(
            int bankId, java.time.LocalDateTime cutoff);
}
