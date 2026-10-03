package com.modocument.infrastructure.persistence;

import com.modocument.application.port.UploadedFileRepository;
import com.modocument.domain.entity.UploadedFile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UploadedFileRepositoryImpl implements UploadedFileRepository {

    private final UploadedFileJpaRepository jpa;

    public UploadedFileRepositoryImpl(UploadedFileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public UploadedFile add(UploadedFile entity) {
        return jpa.save(entity);
    }

    @Override
    public Optional<UploadedFile> getByStoredNameAndOwner(String storedName, int ownerUserId) {
        return jpa.findByStoredNameAndOwnerUserId(storedName, ownerUserId);
    }
}
