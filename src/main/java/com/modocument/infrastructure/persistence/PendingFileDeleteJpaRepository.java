package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.PendingFileDelete;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** ws29 (V290, BUG-12): the bank-scoped retry queue of failed stored-object deletes. */
public interface PendingFileDeleteJpaRepository extends JpaRepository<PendingFileDelete, Long> {

    Optional<PendingFileDelete> findByBankIdAndStorageKey(int bankId, String storageKey);

    /** One bank's rows due for a retry, oldest due first. */
    @Query("select p from PendingFileDelete p where p.bankId = :bankId and p.nextAttemptAt <= :now"
            + " order by p.nextAttemptAt")
    List<PendingFileDelete> findDue(@Param("bankId") int bankId, @Param("now") Instant now,
                                    Pageable page);

    /** The banks with a row due (SYSTEM, ids only). */
    @Query("select distinct p.bankId from PendingFileDelete p where p.nextAttemptAt <= :now")
    List<Integer> findBanksWithDue(@Param("now") Instant now);
}
