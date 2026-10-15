package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocTemplateEdit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocTemplateEditJpaRepository extends JpaRepository<DocTemplateEdit, Integer> {

    List<DocTemplateEdit> findByBankIdAndBlueprintIdAndVersionOrderByDocTemplateEditIdAsc(
            int bankId, int blueprintId, int version);

    /** Rows of [Version, count (Long)] for one blueprint. */
    @Query("select e.version, count(e) from DocTemplateEdit e"
            + " where e.bankId = :bankId and e.blueprintId = :blueprintId group by e.version")
    List<Object[]> countByVersion(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocTemplateEdit e"
            + " where e.bankId = :bankId and e.blueprintId = :blueprintId and e.version = :version")
    int deleteVersion(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId,
                      @Param("version") int version);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocTemplateEdit e where e.bankId = :bankId and e.blueprintId = :blueprintId")
    int deleteBlueprint(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId);
}
