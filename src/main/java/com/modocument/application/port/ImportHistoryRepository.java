package com.modocument.application.port;

import com.modocument.domain.entity.ImportHistory;

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
}
