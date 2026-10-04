package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;

/**
 * One maker/checker change type's hands and feet (ws10). Implementations
 * are thin delegates to the EXISTING use-case logic — no business rule
 * lives here twice:
 * <ul>
 *   <li>{@link #prepare} runs at SUBMIT time, as the maker: it validates
 *       the payload (so the maker gets the 400/404/409 immediately, not the
 *       checker days later) and returns the exact JSON to persist — which
 *       is also where secrets are neutralized (USER_CREATE swaps the
 *       temporary password for its hash);</li>
 *   <li>{@link #apply} runs at APPROVE time, as the checker, inside the
 *       approval's transaction. It parses the persisted payload and calls
 *       the same use case the direct endpoint used to call — which re-runs
 *       every invariant (ownership, bank scoping, last-admin guard),
 *       because the world may have changed since submission.</li>
 * </ul>
 */
public interface PendingChangeApplier {

    /** The one change type this applier handles. */
    ChangeType type();

    /** Validates and normalizes the maker's payload; throws like the direct endpoint would. */
    Prepared prepare(JsonNode payload);

    /** Executes the approved change (same transaction as the approval). */
    void apply(PendingChange change);

    /** What submit persists: the sanitized payload and the target's id (may be null for creates). */
    record Prepared(JsonNode payload, String targetEntityId) {
    }
}
