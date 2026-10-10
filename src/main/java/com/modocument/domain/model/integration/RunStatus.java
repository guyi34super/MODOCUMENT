package com.modocument.domain.model.integration;

/**
 * Lifecycle of an {@code IntegrationRun} (ws25). Stored by NAME.
 *
 * <pre>
 * QUEUED ──▶ RUNNING ──▶ SUCCEEDED | FAILED
 *    └──▶ AWAITING_AGENT ──(agent claims)──▶ RUNNING_AGENT ──▶ SUCCEEDED | FAILED
 * </pre>
 *
 * AGENT-mode runs wait in {@link #AWAITING_AGENT} until the bank's agent
 * long-polls {@code GET /api/agent/v1/tasks}; a run not finished within
 * {@code integration.runTimeout} is FAILED by the engine.
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    AWAITING_AGENT,
    RUNNING_AGENT,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
