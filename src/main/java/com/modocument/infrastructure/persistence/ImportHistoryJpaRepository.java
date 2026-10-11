package com.modocument.infrastructure.persistence;

import com.modocument.application.dto.ImportSummary;
import com.modocument.domain.entity.ImportHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ws28: ImportHistory's {@code template} association is LAZY; the finders
 * that hand entities to the API fetch it in the same statement (the entity
 * still serializes with its template, outside any session).
 */
public interface ImportHistoryJpaRepository extends JpaRepository<ImportHistory, Integer> {

    @EntityGraph(attributePaths = "template")
    Optional<ImportHistory> findFirstByFileNameIgnoreCase(String fileName);

    @EntityGraph(attributePaths = "template")
    Optional<ImportHistory> findByImportHistoryIdAndOwnerUserId(int id, int ownerUserId);

    @EntityGraph(attributePaths = "template")
    List<ImportHistory> findByOwnerUserId(int ownerUserId);

    @EntityGraph(attributePaths = "template")
    Optional<ImportHistory> findFirstByFileNameIgnoreCaseAndOwnerUserId(String fileName, int ownerUserId);

    @Override
    @EntityGraph(attributePaths = "template")
    Optional<ImportHistory> findById(Integer id);

    @Override
    @EntityGraph(attributePaths = "template")
    List<ImportHistory> findAll();

    long countByTemplateId(int templateId);

    /** ws28 (dashboard): how many imports the owner has. */
    long countByOwnerUserId(int ownerUserId);

    /** ws28 (dashboard): the owner's newest imports with their template name, newest first. */
    @Query("select new com.modocument.application.dto.ImportSummary(h.importHistoryId, h.fileName,"
            + " h.templateId, t.name, h.importDate)"
            + " from ImportHistory h left join Template t on t.templateId = h.templateId"
            + " where h.ownerUserId = :ownerUserId"
            + " order by h.importDate desc, h.importHistoryId desc")
    List<ImportSummary> findRecentSummaries(@Param("ownerUserId") int ownerUserId, Pageable pageable);
}
