package com.modocument.infrastructure.extraction.sql;

import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.infrastructure.extraction.sql.entity.ExtractionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExtractionJobJpaRepository extends JpaRepository<ExtractionJobEntity, Long> {

    Optional<ExtractionJobEntity> findByIdAndOwnerUserId(Long id, int ownerUserId);

    List<ExtractionJobEntity> findByOwnerUserIdOrderByIdDesc(int ownerUserId);

    List<ExtractionJobEntity> findAllByOrderByIdDesc();

    List<ExtractionJobEntity> findByStatusIn(Collection<ExtractionJobStatus> statuses);
}
