package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UploadedFileJpaRepository extends JpaRepository<UploadedFile, Integer> {

    Optional<UploadedFile> findByStoredNameAndOwnerUserId(String storedName, int ownerUserId);
}
