package com.modocument.infrastructure.persistence;

import com.modocument.domain.entity.IntegrationEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** ws25 integration event stream (V252); every query names the bank. */
public interface IntegrationEventJpaRepository extends JpaRepository<IntegrationEvent, Long> {

    @Query("SELECT e FROM IntegrationEvent e WHERE e.bankId = :bankId AND e.id > :afterId "
            + "ORDER BY e.id ASC")
    List<IntegrationEvent> page(@Param("bankId") int bankId, @Param("afterId") long afterId,
                                Pageable page);

    @Query("SELECT e FROM IntegrationEvent e WHERE e.bankId = :bankId AND e.id >= :fromId "
            + "AND e.id <= :toId ORDER BY e.id ASC")
    List<IntegrationEvent> range(@Param("bankId") int bankId, @Param("fromId") long fromId,
                                 @Param("toId") long toId, Pageable page);

    Optional<IntegrationEvent> findByIdAndBankId(long id, int bankId);
}
