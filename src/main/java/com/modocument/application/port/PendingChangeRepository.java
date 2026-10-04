package com.modocument.application.port;

import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.PendingChangeStatus;

import java.util.List;
import java.util.Optional;

/**
 * Pending maker/checker changes (V80, ws10). On PostgreSQL the table is
 * under RLS, but the bank filters here are the H2-test and defense-in-depth
 * layer — callers must always pass the caller's own bank.
 */
public interface PendingChangeRepository {

    PendingChange save(PendingChange entity);

    Optional<PendingChange> getById(int id);

    /** Every change of one bank, newest first. */
    List<PendingChange> listByBank(int bankId);

    /** One bank's changes in one status, newest first. */
    List<PendingChange> listByBankAndStatus(int bankId, PendingChangeStatus status);
}
