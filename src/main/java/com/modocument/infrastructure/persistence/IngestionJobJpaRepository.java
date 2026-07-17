package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobJpaRepository extends JpaRepository<IngestionJob, Integer> {
}
