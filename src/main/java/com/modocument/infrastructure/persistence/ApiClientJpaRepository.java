package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ws26 API clients (V260). Bank-scoped reads name the bank explicitly on top
 * of row level security (the H2 suite has no RLS). The {@code findBy*} lookups
 * by credential are the PRE-TENANT ones: only {@code ApiClientAuthenticator}
 * calls them, as SYSTEM (a registered TenantContext.runAsSystem call site).
 */
public interface ApiClientJpaRepository extends JpaRepository<ApiClient, Integer> {

    Optional<ApiClient> findByClientId(String clientId);

    Optional<ApiClient> findByKeyPrefix(String keyPrefix);

    Optional<ApiClient> findByCertFingerprintSha256(String certFingerprintSha256);

    boolean existsByCertFingerprintSha256(String certFingerprintSha256);

    boolean existsByClientId(String clientId);

    boolean existsByKeyPrefix(String keyPrefix);

    Optional<ApiClient> findByApiClientIdAndBankId(int apiClientId, int bankId);

    @Query("SELECT c FROM ApiClient c WHERE c.bankId = :bankId ORDER BY c.apiClientId DESC")
    List<ApiClient> findByBank(@Param("bankId") int bankId);

    /** LastUsedAt, at most once per {@code threshold} (no write per request). */
    @Modifying
    @Query("UPDATE ApiClient c SET c.lastUsedAt = :now WHERE c.apiClientId = :id "
            + "AND c.bankId = :bankId AND (c.lastUsedAt IS NULL OR c.lastUsedAt < :threshold)")
    int touchLastUsed(@Param("id") int apiClientId, @Param("bankId") int bankId,
                      @Param("now") Instant now, @Param("threshold") Instant threshold);
}
