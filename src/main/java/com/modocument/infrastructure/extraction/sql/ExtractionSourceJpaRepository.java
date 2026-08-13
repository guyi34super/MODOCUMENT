package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.sql.entity.ExtractionSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExtractionSourceJpaRepository extends JpaRepository<ExtractionSourceEntity, Long> {

    Optional<ExtractionSourceEntity> findByIdAndOwnerUserId(Long id, int ownerUserId);

    List<ExtractionSourceEntity> findByOwnerUserIdOrderByIdDesc(int ownerUserId);

    List<ExtractionSourceEntity> findAllByOrderByIdDesc();
}
