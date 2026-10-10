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

    /**
     * ws22: not-yet-finished jobs (PENDING / RUNNING) of users OTHER than
     * {@code ownerUserId} on the template — a bank-shared template in active
     * use by colleagues cannot be deleted or made private (409).
     */
    int countActiveByTemplateIdOfOtherUsers(int templateId, int ownerUserId);

    /** Removes the job row entirely (ws14 job deletion, gate C5). */
    void delete(IngestionJob job);

    /**
     * Jobs of one owner referencing the same stored upload — deleting a job
     * only removes the shared file when no other job still needs it.
     */
    int countByFileNameAndOwner(String fileName, int ownerUserId);
}
