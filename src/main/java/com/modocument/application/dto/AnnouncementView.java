package com.modocument.application.dto;

import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;

import java.time.Instant;

/**
 * One banner as a bank user receives it from
 * {@code GET /api/announcements/active} (ws24, docs/phase2/contracts.md §3).
 * {@code title} and {@code body} are plain text: render them escaped.
 * {@code dismissible} is false for MAINTENANCE.
 */
public record AnnouncementView(int id, AnnouncementKind kind, AnnouncementSeverity severity,
                               String title, String body, Instant startsAt, Instant endsAt,
                               Instant maintenanceStartsAt, Instant maintenanceEndsAt,
                               String releaseVersion, boolean dismissible) {
}
