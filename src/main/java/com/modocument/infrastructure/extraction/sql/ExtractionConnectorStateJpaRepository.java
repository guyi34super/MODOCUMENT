package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.sql.entity.ExtractionConnectorStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionConnectorStateJpaRepository
        extends JpaRepository<ExtractionConnectorStateEntity, String> {
}
