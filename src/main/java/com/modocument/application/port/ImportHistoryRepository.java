package com.modocument.application.port;

import com.modocument.domain.entity.ImportHistory;

import java.util.List;
import java.util.Optional;

public interface ImportHistoryRepository {

    Optional<ImportHistory> getById(int id);

    List<ImportHistory> list();

    ImportHistory add(ImportHistory entity);

    ImportHistory update(ImportHistory entity);

    void delete(int id);

    Optional<ImportHistory> getByFileName(String fileName);

    /** Imports referencing a template — used before deleting the template. */
    int countByTemplateId(int templateId);
}
