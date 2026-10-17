package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocDataFieldRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** V330: the rules of data template fields; every call names the caller's bank. */
public interface DocDataFieldRuleJpaRepository extends JpaRepository<DocDataFieldRule, Long> {

    List<DocDataFieldRule> findByBankIdAndDataTemplateIdOrderByFieldNameAscSortOrderAscDocDataFieldRuleIdAsc(
            int bankId, int dataTemplateId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocDataFieldRule r where r.bankId = :bankId and r.dataTemplateId = :dataTemplateId")
    int deleteTemplate(@Param("bankId") int bankId, @Param("dataTemplateId") int dataTemplateId);
}
