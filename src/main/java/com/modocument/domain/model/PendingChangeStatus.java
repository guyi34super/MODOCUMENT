package com.modocument.domain.model;

/**
 * Lifecycle of a {@code PendingChange} (ws10 maker/checker). REQUESTED is
 * the only state a decision can act on; everything else is terminal.
 */
public enum PendingChangeStatus {

    /** Submitted by the maker, awaiting a different user's decision. */
    REQUESTED,
    /**
     * Approved by a checker. Transient: the synchronous apply inside the
     * same transaction moves the row straight on to {@link #APPLIED}, so
     * this value is never observed at rest — it exists so an asynchronous
     * applier could be introduced without a schema change.
     */
    APPROVED,
    /** A checker refused the change; nothing was applied. */
    REJECTED,
    /** The maker withdrew the request before any decision. */
    CANCELLED,
    /** Approved by a different user and executed. */
    APPLIED,
    /**
     * Approved, but the apply hit an invariant that changed since submission
     * (e.g. the last-admin guard); the domain change rolled back and the
     * request is spent — resubmit once the state allows it.
     */
    FAILED
}
