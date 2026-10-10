package com.modocument.infrastructure.extraction.sql;

import com.modocument.infrastructure.extraction.sql.entity.ExtractionJobCounterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ExtractionJobCounterJpaRepository
        extends JpaRepository<ExtractionJobCounterEntity, ExtractionJobCounterEntity.Key> {

    /**
     * Server-side add so concurrent workers never lose increments. Runs in its
     * own committed transaction: the caller retries after an insert race, and
     * that retry must see the racing winner's committed row.
     */
    @Transactional
    @Modifying
    @Query("update ExtractionJobCounterEntity c set c.value = c.value + :delta "
            + "where c.jobId = :jobId and c.name = :name")
    int increment(@Param("jobId") long jobId, @Param("name") String name,
                  @Param("delta") long delta);

    /**
     * Creates a counter row with a plain INSERT. Not {@code save()}: the key is
     * assigned, so {@code save()} would MERGE, and a row a racing worker just
     * committed would be overwritten with {@code value} instead of failing on
     * the primary key (the lost-increment bug this replaces).
     */
    @Transactional
    @Modifying
    @Query(value = "insert into ExtractionJobCounter (JobId, Name, CounterValue, BankId) "
            + "values (:jobId, :name, :value, :bankId)", nativeQuery = true)
    int insert(@Param("jobId") long jobId, @Param("name") String name,
               @Param("value") long value, @Param("bankId") int bankId);

    List<ExtractionJobCounterEntity> findByJobId(long jobId);

    List<ExtractionJobCounterEntity> findByJobIdIn(Collection<Long> jobIds);
}
