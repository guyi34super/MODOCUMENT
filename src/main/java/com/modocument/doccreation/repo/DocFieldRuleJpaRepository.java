package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocFieldRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** V321: the field rules of PDF templates; every call names the caller's bank. */
public interface DocFieldRuleJpaRepository extends JpaRepository<DocFieldRule, Long> {

    List<DocFieldRule> findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(
            int bankId, int blueprintId);

    /** Rows of (blueprintId, rule count) for the given templates of the bank (the templates list). */
    @Query("select r.blueprintId, count(r) from DocFieldRule r where r.bankId = :bankId "
            + "and r.blueprintId in :blueprintIds group by r.blueprintId")
    List<Object[]> countByBlueprints(@Param("bankId") int bankId,
                                     @Param("blueprintIds") java.util.Collection<Integer> blueprintIds);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocFieldRule r where r.bankId = :bankId and r.blueprintId = :blueprintId")
    int deleteBlueprint(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId);
}
