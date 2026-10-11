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

    /**
     * APPLIED rows violating the B7/SoD invariant — approved by their own
     * maker. The nightly SodScanJob (ws11, SEC-024 layer 3) expects this to
     * be empty for every bank; the runtime gate in PendingChangeUseCase
     * makes it so.
     */
    List<PendingChange> listAppliedSodViolations(int bankId);

    /**
     * ws28: one page (zero-based) of the bank's changes the caller may see,
     * newest first — those {@code makerUserId} submitted, plus every change
     * whose type is in {@code decidableTypes} — optionally in one status
     * (null = any). The visibility filter, the paging and the total run in
     * the database; the default serves in-memory fakes.
     */
    default com.modocument.application.dto.PagedList<PendingChange> pageVisible(
            int bankId, PendingChangeStatus status, int makerUserId,
            java.util.Collection<com.modocument.domain.model.ChangeType> decidableTypes,
            int page, int size) {
        List<PendingChange> visible = (status == null ? listByBank(bankId) : listByBankAndStatus(bankId, status))
                .stream()
                .filter(row -> row.getMakerUserId() == makerUserId
                        || decidableTypes.contains(row.getChangeType()))
                .toList();
        return new com.modocument.application.dto.PagedList<>(visible.stream()
                .skip((long) page * size).limit(size).toList(), page, size, visible.size());
    }
}
