package com.modocument.doccreation.repo;

import com.modocument.doccreation.entity.DocInstance;

import java.util.List;
import java.util.Optional;

public interface DocInstanceRepository {

    /** Owner-scoped read: empty for an instance the caller does not own. */
    Optional<DocInstance> getByIdAndOwner(int id, int ownerUserId);

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

    DocInstance save(DocInstance entity);

    void delete(int id);
}
