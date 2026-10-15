package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocTemplateRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DocTemplateRegionJpaRepository extends JpaRepository<DocTemplateRegion, Integer> {

    List<DocTemplateRegion> findByBankIdAndBlueprintIdAndVersionOrderBySortOrderAscDocTemplateRegionIdAsc(
            int bankId, int blueprintId, int version);

    List<DocTemplateRegion> findByBankIdAndBlueprintIdInOrderByBlueprintIdAscVersionAscSortOrderAsc(
            int bankId, Collection<Integer> blueprintIds);

    /** Rows of [Version, count (Long)] for one blueprint. */
    @Query("select r.version, count(r) from DocTemplateRegion r"
            + " where r.bankId = :bankId and r.blueprintId = :blueprintId group by r.version")
    List<Object[]> countByVersion(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId);

    /** A bulk delete, so re-saving the same keys in the same transaction cannot collide. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocTemplateRegion r"
            + " where r.bankId = :bankId and r.blueprintId = :blueprintId and r.version = :version")
    int deleteVersion(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId,
                      @Param("version") int version);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocTemplateRegion r where r.bankId = :bankId and r.blueprintId = :blueprintId")
    int deleteBlueprint(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId);
}
