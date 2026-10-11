package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ws25 connections (V250). Every query names the bank explicitly on top of
 * row level security, so the H2 suite (no RLS) filters exactly like
 * PostgreSQL.
 */
public interface IntegrationConnectionJpaRepository
        extends JpaRepository<IntegrationConnection, Long> {

    List<IntegrationConnection> findByBankIdOrderByNameAsc(int bankId);

    Optional<IntegrationConnection> findByIdAndBankId(long id, int bankId);

    boolean existsByBankIdAndName(int bankId, String name);
}
