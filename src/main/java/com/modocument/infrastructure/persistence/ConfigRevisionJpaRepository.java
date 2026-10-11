package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.ConfigRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * The single-row settings change counter (V200, ws20). Tenant-less: no RLS.
 */
public interface ConfigRevisionJpaRepository extends JpaRepository<ConfigRevision, Short> {

    /**
     * Bumps the counter in the caller's transaction (the row lock serializes
     * concurrent settings writers until commit).
     *
     * @return rows updated — 0 when the row is missing (fresh H2 schema)
     */
    @Modifying
    @Query("UPDATE ConfigRevision c SET c.revision = c.revision + 1, c.updatedAt = :now "
            + "WHERE c.id = 1")
    int bump(@Param("now") Instant now);
}
