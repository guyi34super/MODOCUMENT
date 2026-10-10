package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ApiClientInitialSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ws26 one-time initial secrets (V260), sealed, deleted once read or expired. */
public interface ApiClientInitialSecretJpaRepository
        extends JpaRepository<ApiClientInitialSecret, Integer> {

    Optional<ApiClientInitialSecret> findByApiClientIdAndBankId(int apiClientId, int bankId);

    @Query("SELECT s.apiClientId FROM ApiClientInitialSecret s WHERE s.bankId = :bankId "
            + "AND s.recipientUserId = :userId AND s.expiresAt > :now")
    List<Integer> findDeliverable(@Param("bankId") int bankId, @Param("userId") int userId,
                                  @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM ApiClientInitialSecret s WHERE s.bankId = :bankId AND s.expiresAt <= :now")
    int deleteExpired(@Param("bankId") int bankId, @Param("now") Instant now);
}
