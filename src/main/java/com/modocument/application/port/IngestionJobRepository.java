package com.modocument.application.port;

import com.modocument.domain.entity.IngestionJob;

import java.util.List;
import java.util.Optional;

public interface IngestionJobRepository {

    Optional<IngestionJob> getById(int id);

    List<IngestionJob> list();

    IngestionJob save(IngestionJob job);

    /**
     * MappingJson of the most recent jobs for a template that reached
     * VALIDATED or EXPORTED — the learned-mappings signal for auto-mapping.
     */
    List<String> listRecentMappingJson(int templateId, int limit);

    /** Jobs referencing a template — used before deleting the template. */
    int countByTemplateId(int templateId);
}
