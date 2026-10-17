package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocDataField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** V330: the fields of data templates; every call names the caller's bank. */
public interface DocDataFieldJpaRepository extends JpaRepository<DocDataField, Long> {

    List<DocDataField> findByBankIdAndDataTemplateIdOrderBySortOrderAscDocDataFieldIdAsc(int bankId, int dataTemplateId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocDataField f where f.bankId = :bankId and f.dataTemplateId = :dataTemplateId")
    int deleteTemplate(@Param("bankId") int bankId, @Param("dataTemplateId") int dataTemplateId);

    /** Rows of [DataTemplateId, number of fields] of several data templates of a bank. */
    @Query("select f.dataTemplateId, count(f) from DocDataField f where f.bankId = :bankId"
            + " and f.dataTemplateId in :dataTemplateIds group by f.dataTemplateId")
    List<Object[]> countByTemplate(@Param("bankId") int bankId,
                                   @Param("dataTemplateIds") Collection<Integer> dataTemplateIds);
}
