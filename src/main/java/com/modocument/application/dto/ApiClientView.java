package com.modocument.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * An API client as the bank admin sees it (ws26, contracts §7.3). Secrets
 * are never part of it. {@code status}: ACTIVE | REVOKED | EXPIRED, or
 * PENDING for a create request still waiting for its checker (then
 * {@code id}/{@code clientId} are null and {@code pendingChangeId} is set).
 * {@code initialSecretAvailable} is true only for the maker, while the
 * one-time secret of an approved client is still waiting to be read.
 */
public record ApiClientView(Integer id, String name, String kind, String authMethod,
                            String clientId, String keyPrefix, String certFingerprint,
                            String certSubject, List<String> scopes, List<String> allowedCidrs,
                            Instant expiresAt, String status, Instant lastUsedAt,
                            Integer createdBy, String createdByEmail, Integer pendingChangeId,
                            boolean initialSecretAvailable) {
}
