package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocBlueprintJpaRepository extends JpaRepository<DocBlueprint, Integer> {

    Optional<DocBlueprint> findByDocBlueprintIdAndOwnerUserId(int id, int ownerUserId);

    List<DocBlueprint> findByOwnerUserIdOrderByDocBlueprintIdDesc(int ownerUserId);

    List<DocBlueprint> findByOwnerUserIdAndKindOrderByDocBlueprintIdDesc(int ownerUserId, String kind);

    List<DocBlueprint> findByBankIdAndKindOrderByDocBlueprintIdAsc(int bankId, String kind);

    /** ws34: the PDF templates of a bank that chose a data template (data-templates-contract §2). */
    List<DocBlueprint> findByBankIdAndDataTemplateIdOrderByDocBlueprintIdAsc(int bankId, Integer dataTemplateId);

    /** ws34: rows of [DataTemplateId, number of PDF templates that chose it] of a bank. */
    @Query("select b.dataTemplateId, count(b) from DocBlueprint b where b.bankId = :bankId"
            + " and b.dataTemplateId in :dataTemplateIds group by b.dataTemplateId")
    List<Object[]> countByDataTemplate(@Param("bankId") int bankId,
                                       @Param("dataTemplateIds") Collection<Integer> dataTemplateIds);

    /**
     * ws34: raises the RulesRevision of every PDF template of the bank that
     * chose the data template by one (its fields or rules were saved,
     * data-templates-contract §2), so documents validated earlier see that
     * the rules changed.
     *
     * @return the number of PDF templates changed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update DocBlueprint b set b.rulesRevision = b.rulesRevision + 1"
            + " where b.bankId = :bankId and b.dataTemplateId = :dataTemplateId")
    int bumpRulesRevisionOfDataTemplate(@Param("bankId") int bankId, @Param("dataTemplateId") int dataTemplateId);
}
