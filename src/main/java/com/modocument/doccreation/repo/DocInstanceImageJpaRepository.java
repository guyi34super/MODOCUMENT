package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstanceImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocInstanceImageJpaRepository extends JpaRepository<DocInstanceImage, Integer> {

    Optional<DocInstanceImage> findByDocInstanceImageIdAndBankId(int id, int bankId);

    List<DocInstanceImage> findByBankIdAndInstanceIdOrderBySortOrderAscDocInstanceImageIdAsc(int bankId, int instanceId);

    List<DocInstanceImage> findByBankIdAndInstanceIdInOrderByInstanceIdAscSortOrderAsc(
            int bankId, Collection<Integer> instanceIds);

    /** Retention purge (ws14): every photo row of one instance. */
    List<DocInstanceImage> findByInstanceId(int instanceId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocInstanceImage i where i.bankId = :bankId and i.instanceId = :instanceId")
    int deleteInstance(@Param("bankId") int bankId, @Param("instanceId") int instanceId);
}
