package com.modocument.application.dto;

/**
 * Integration health of one bank as the platform console sees it
 * ({@code GET /api/platform/banks/{id}}, wave 4). COUNTS ONLY — no
 * connection name, host, flow, payload or error text.
 *
 * @param connections        the bank's integration connections
 * @param connectionsFailing of those, the ones whose last test FAILED
 * @param flowsEnabled       enabled flows
 * @param runs24h            runs finished in the last 24 h: SUCCEEDED / FAILED
 * @param deadLetters        outbound deliveries in DEAD status (awaiting replay)
 * @param agentsOffline      enrolled agents whose last heartbeat is older than
 *                           the bank's {@code integration.agentHeartbeatTimeout}
 *                           (or that never sent one)
 */
public record BankIntegrations(long connections, long connectionsFailing, long flowsEnabled,
                               Runs runs24h, long deadLetters, long agentsOffline) {

    public record Runs(long ok, long failed) {
    }
}
