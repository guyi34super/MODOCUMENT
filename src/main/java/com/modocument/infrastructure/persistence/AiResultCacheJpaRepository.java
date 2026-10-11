package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.AiResultCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** ws28 (V286): the bank-scoped AI result cache. RLS backs the bank filters on PostgreSQL. */
public interface AiResultCacheJpaRepository extends JpaRepository<AiResultCacheEntry, Long> {

    Optional<AiResultCacheEntry> findByBankIdAndCacheKey(int bankId, String cacheKey);

    /** Counts one reuse of a cached result. */
    @Transactional
    @Modifying
    @Query("update AiResultCacheEntry e set e.hitCount = e.hitCount + 1 where e.id = :id")
    int incrementHits(@Param("id") long id);

    /** Retention (RetentionPurger): one bank's cached results created before the cutoff. */
    @Modifying
    @Query("delete from AiResultCacheEntry e where e.bankId = :bankId and e.createdAt < :cutoff")
    int purgeOlderThan(@Param("bankId") int bankId, @Param("cutoff") Instant cutoff);
}
