package com.modocument.infrastructure.persistence;

import com.modocument.application.port.IngestionJobRepository;
import com.modocument.domain.entity.IngestionJob;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class IngestionJobRepositoryImpl implements IngestionJobRepository {

    private final IngestionJobJpaRepository jpa;

    public IngestionJobRepositoryImpl(IngestionJobJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<IngestionJob> getById(int id) {
        return jpa.findById(id);
    }

    @Override
    public List<IngestionJob> list() {
        return jpa.findAll();
    }

    @Override
    public IngestionJob save(IngestionJob job) {
        if (job.getCreatedDate() == null) {
            job.setCreatedDate(LocalDateTime.now());
        }
        job.setUpdatedDate(LocalDateTime.now());
        return jpa.save(job);
    }
}
