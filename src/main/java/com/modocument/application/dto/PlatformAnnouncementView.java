package com.modocument.application.dto;

import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;

import java.time.Instant;
import java.util.List;

/**
 * One announcement as the platform console manages it
 * ({@code GET|POST /api/platform/announcements}, {@code PUT …/{id}}; ws24).
 *
 * @param audienceBankCodes null = every bank, else the bank codes that see it
 * @param status            SCHEDULED (before its start), ACTIVE, ENDED or
 *                          ARCHIVED, as of the response
 * @param createdBy         AppUserId of the platform admin; null when the
 *                          deployment recorder created it
 */
public record PlatformAnnouncementView(int id, AnnouncementKind kind, AnnouncementSeverity severity,
                                       String title, String body, List<String> audienceBankCodes,
                                       Instant startsAt, Instant endsAt,
                                       Instant maintenanceStartsAt, Instant maintenanceEndsAt,
                                       String releaseVersion, boolean archived, String status,
                                       Integer createdBy, Instant createdAt,
                                       Integer updatedBy, Instant updatedAt) {
}
