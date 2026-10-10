package com.modocument.application.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * API view of a maker/checker pending change (ws10). {@code payload} is the
 * SANITIZED request — credential material (the USER_CREATE password hash)
 * is stripped before anything leaves the backend, so neither the checker's
 * review nor the maker's own listing ever shows it.
 */
public record PendingChangeDto(int pendingChangeId, String changeType, String targetEntityType,
                               String targetEntityId, String status, int makerUserId,
                               String makerEmail, String makerComment, Integer checkerUserId,
                               String checkerEmail, String checkerComment,
                               LocalDateTime requestedAt, LocalDateTime decidedAt,
                               LocalDateTime appliedAt, JsonNode payload) {
}
