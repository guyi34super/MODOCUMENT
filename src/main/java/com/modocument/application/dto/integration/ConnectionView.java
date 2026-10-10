package com.modocument.application.dto.integration;

import java.time.Instant;
import java.util.Map;

/**
 * A connection as the admin sees it (ws25, contracts §7.3). Secrets are
 * write-only: {@code secretSet} says whether one is stored, never what.
 */
public record ConnectionView(long id, String name, String type, String mode, Integer agentId,
                             Map<String, Object> config, String authType, boolean secretSet,
                             String secretRef, String status, Instant lastTestAt,
                             String lastTestResult, int version, Integer pendingChangeId,
                             int createdBy, Instant updatedAt) {
}
