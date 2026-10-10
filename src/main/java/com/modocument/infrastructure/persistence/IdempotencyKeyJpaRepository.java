package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** ws26 Idempotency-Key replay store (V260), per (bank, client, key). */
public interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByBankIdAndApiClientIdAndIdemKey(int bankId, int apiClientId,
                                                                 String idemKey);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.bankId = :bankId AND k.expiresAt <= :now")
    int deleteExpired(@Param("bankId") int bankId, @Param("now") Instant now);
}
