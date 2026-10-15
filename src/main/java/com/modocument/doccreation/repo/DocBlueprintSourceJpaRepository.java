package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocBlueprintSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** V311: the saved data sources of PDF templates; every call names the caller's bank. */
public interface DocBlueprintSourceJpaRepository extends JpaRepository<DocBlueprintSource, Long> {

    List<DocBlueprintSource> findByBankIdAndBlueprintIdOrderBySortOrderAscDocBlueprintSourceIdAsc(
            int bankId, int blueprintId);

    Optional<DocBlueprintSource> findByDocBlueprintSourceIdAndBankIdAndBlueprintId(
            Long id, int bankId, int blueprintId);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from DocBlueprintSource s where s.bankId = :bankId and s.blueprintId = :blueprintId")
    int deleteBlueprint(@Param("bankId") int bankId, @Param("blueprintId") int blueprintId);
}
