package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ImportHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImportHistoryJpaRepository extends JpaRepository<ImportHistory, Integer> {

    Optional<ImportHistory> findFirstByFileNameIgnoreCase(String fileName);
}
