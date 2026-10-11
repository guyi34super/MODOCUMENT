package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** ws25 run log (V250). */
public interface IntegrationRunEventJpaRepository extends JpaRepository<IntegrationRunEvent, Long> {

    List<IntegrationRunEvent> findByBankIdAndRunIdOrderByIdAsc(int bankId, long runId);

    @Modifying
    @Query("DELETE FROM IntegrationRunEvent e WHERE e.bankId = :bankId AND e.runId IN :runIds")
    int deleteByRunIds(@Param("bankId") int bankId, @Param("runIds") Collection<Long> runIds);
}
