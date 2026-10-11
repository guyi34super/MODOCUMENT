package com.modocument.application.port;

import com.modocument.application.dto.ImportSummary;
import com.modocument.domain.entity.ImportHistory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface ImportHistoryRepository {

    Optional<ImportHistory> getById(int id);

    /** Owner-scoped read: empty for an import the caller does not own. */
    Optional<ImportHistory> getByIdAndOwner(int id, int ownerUserId);

    List<ImportHistory> list();

    List<ImportHistory> listByOwner(int ownerUserId);

    ImportHistory add(ImportHistory entity);

    ImportHistory update(ImportHistory entity);

    void delete(int id);

    Optional<ImportHistory> getByFileName(String fileName);

    /** Owner-scoped filename lookup — used by auto-mapping. */
    Optional<ImportHistory> getByFileNameAndOwner(String fileName, int ownerUserId);

    /** Imports referencing a template — used before deleting the template. */
    int countByTemplateId(int templateId);

    /** ws28 (dashboard): how many imports the owner has. */
    default long countByOwner(int ownerUserId) {
        return listByOwner(ownerUserId).size();
    }

    /** ws28 (dashboard): the owner's {@code limit} newest imports, newest first. */
    default List<ImportSummary> listRecentSummariesByOwner(int ownerUserId, int limit) {
        return listByOwner(ownerUserId).stream()
                .sorted(Comparator.comparing(ImportHistory::getImportDate,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(ImportHistory::getImportHistoryId).reversed())
                .limit(limit)
                .map(h -> new ImportSummary(h.getImportHistoryId(), h.getFileName(), h.getTemplateId(),
                        h.getTemplate() == null ? null : h.getTemplate().getName(), h.getImportDate()))
                .toList();
    }
}
