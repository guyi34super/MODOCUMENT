package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;

import java.util.List;
import java.util.Optional;

public interface DocInstanceRepository {

    /** Owner-scoped read: empty for an instance the caller does not own. */
    Optional<DocInstance> getByIdAndOwner(int id, int ownerUserId);

    /**
     * ws33: {@link #getByIdAndOwner} with the row locked for the rest of the
     * transaction (PESSIMISTIC_WRITE); validate and every content change use it.
     */
    default Optional<DocInstance> getByIdAndOwnerForUpdate(int id, int ownerUserId) {
        return getByIdAndOwner(id, ownerUserId);
    }

    /**
     * Bank-wide read (ws11 release workflow): approvers review OTHER users'
     * submitted documents, so the owner filter does not apply — the bank
     * filter is the boundary (RLS-covered on PostgreSQL; this explicit
     * filter is the H2-test and defense-in-depth layer). Callers must always
     * pass the caller's own bank.
     */
    Optional<DocInstance> getByIdAndBank(int id, int bankId);

    /** One bank's instances in one status, newest first (the release queue). */
    List<DocInstance> listByBankAndStatus(int bankId, String status);

    /**
     * RELEASED rows violating the ws11 SoD invariant — released by their own
     * author (owner) or their submitter. The nightly SodScanJob expects this
     * to be empty for every bank (SEC-024 layer 3).
     */
    List<DocInstance> listReleasedSodViolations(int bankId);

    List<DocInstance> listByOwner(int ownerUserId);

    List<DocInstance> listByOwnerAndBlueprint(int ownerUserId, int blueprintId);

    int countByBlueprint(int blueprintId);

    /** ws31 legacy purge: every instance (any owner) of one blueprint in the given bank. */
    List<DocInstance> listByBankAndBlueprint(int bankId, int blueprintId);

    DocInstance save(DocInstance entity);

    void delete(int id);

    /** ws28: how many instances each blueprint has (blueprints without instances are absent). */
    default java.util.Map<Integer, Long> countByBlueprints(java.util.Collection<Integer> blueprintIds) {
        java.util.Map<Integer, Long> counts = new java.util.LinkedHashMap<>();
        for (Integer id : blueprintIds) {
            long count = countByBlueprint(id);
            if (count > 0) {
                counts.put(id, count);
            }
        }
        return counts;
    }

    /** V291: how many instances (all owners) build on each version of one blueprint, keyed by version. */
    default java.util.Map<Integer, Long> countByBlueprintVersion(int blueprintId) {
        return java.util.Map.of();
    }
}
