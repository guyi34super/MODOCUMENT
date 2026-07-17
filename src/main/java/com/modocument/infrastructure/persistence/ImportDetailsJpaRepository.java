package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ImportDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportDetailsJpaRepository extends JpaRepository<ImportDetails, Integer> {
}
