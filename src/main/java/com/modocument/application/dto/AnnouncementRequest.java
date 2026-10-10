package com.modocument.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code POST /api/platform/announcements} and
 * {@code PUT /api/platform/announcements/{id}} (ws24, PLATFORM_ANNOUNCE).
 * Validated by the use case (AnnouncementRules), which answers 400
 * {@code ANNOUNCEMENT_INVALID} with a readable message:
 * <ul>
 *   <li>{@code kind} INFO | MAINTENANCE | RELEASE; {@code severity} INFO |
 *       WARNING | CRITICAL;</li>
 *   <li>{@code title} 1–200 characters, {@code body} up to 2000 (optional),
 *       plain text — no control characters other than line breaks and
 *       tabs in the body;</li>
 *   <li>{@code startsAt} (default: now) before {@code endsAt} (required,
 *       in the future), at most 366 days apart; ISO-8601 instants;</li>
 *   <li>MAINTENANCE needs {@code maintenanceStartsAt} before
 *       {@code maintenanceEndsAt}, both inside the display window; other
 *       kinds must leave them empty;</li>
 *   <li>RELEASE needs {@code releaseVersion} X.Y.Z; other kinds leave it
 *       empty;</li>
 *   <li>{@code audienceBankCodes} null or empty = every bank, else up to
 *       100 bank codes.</li>
 * </ul>
 */
public record AnnouncementRequest(String kind, String severity, String title, String body,
                                  List<String> audienceBankCodes,
                                  Instant startsAt, Instant endsAt,
                                  Instant maintenanceStartsAt, Instant maintenanceEndsAt,
                                  String releaseVersion) {
}
