package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserId(int userId);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :when "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("when") Instant when);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :when "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") int userId, @Param("when") Instant when);

    /**
     * ws21 bank suspension: revokes every live refresh token of every account
     * of the bank (RefreshToken carries no BankId; the bank is resolved
     * through AppUsers).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :when WHERE t.revokedAt IS NULL "
            + "AND t.userId IN (SELECT u.appUserId FROM AppUser u WHERE u.bankId = :bankId)")
    int revokeAllForBank(@Param("bankId") int bankId, @Param("when") Instant when);
}
