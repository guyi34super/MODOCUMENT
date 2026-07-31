package com.modocument.application.port;

import com.modocument.domain.entity.IngestionJob;

import java.util.List;
import java.util.Optional;

public interface IngestionJobRepository {

    Optional<IngestionJob> getById(int id);

    /** Owner-scoped read: empty for a job the caller does not own. */
    Optional<IngestionJob> getByIdAndOwner(int id, int ownerUserId);

    List<IngestionJob> list();

    List<IngestionJob> listByOwner(int ownerUserId);

    IngestionJob save(IngestionJob job);

    /**
     * MappingJson of the most recent jobs for a template that reached
     * VALIDATED or EXPORTED — the learned-mappings signal for auto-mapping.
     * Scoped to the owner so one user's history never trains another's mapping.
     */
    List<String> listRecentMappingJson(int templateId, int ownerUserId, int limit);

    /** Jobs referencing a template — used before deleting the template. */
    int countByTemplateId(int templateId);
}
