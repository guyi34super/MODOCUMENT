package com.modocument.application.dto;

import java.time.Instant;

/**
 * The one-time enrolment of a registered agent (ws26,
 * {@code GET /api/admin/integrations/agents/{id}/enrolment}): readable once,
 * by the maker of the AGENT_REGISTER change, until {@code expiresAt}.
 * {@code dockerCommand} is ready to paste on the bank's host.
 * {@link #toString()} never prints the token.
 */
public record AgentEnrolmentView(String token, Instant expiresAt, String dockerCommand) {

    @Override
    public String toString() {
        return "AgentEnrolmentView[expiresAt=" + expiresAt + "]";
    }
}
