package com.modocument.infrastructure.release;

import com.modocument.domain.entity.Announcement;
import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An immutable copy of one live announcement as held by
 * {@link AnnouncementCache} — never the managed entity, so a cached value
 * can be shared across request threads safely.
 *
 * @param audience the bank codes it is shown to; empty = every bank
 */
public record LiveAnnouncement(int id, AnnouncementKind kind, AnnouncementSeverity severity,
                               String title, String body, Set<String> audience,
                               Instant startsAt, Instant endsAt,
                               Instant maintenanceStartsAt, Instant maintenanceEndsAt,
                               String releaseVersion) {

    public LiveAnnouncement {
        audience = Set.copyOf(audience);
    }

    static LiveAnnouncement of(Announcement row) {
        return new LiveAnnouncement(row.getAnnouncementId(), row.getKind(), row.getSeverity(),
                row.getTitle(), row.getBody(), parseAudience(row.getAudienceBankCodes()),
                row.getStartsAt(), row.getEndsAt(),
                row.getMaintenanceStartsAt(), row.getMaintenanceEndsAt(),
                row.getReleaseVersion());
    }

    /** Within its display window at {@code now} (start inclusive, end exclusive). */
    public boolean activeAt(Instant now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /** Whether a user of the bank with this code may see it (null code: only "every bank"). */
    public boolean visibleTo(String bankCode) {
        return audience.isEmpty() || (bankCode != null && audience.contains(bankCode));
    }

    /** {@code "A,B"} → {A, B}; blank entries dropped; null or blank → every bank. */
    public static Set<String> parseAudience(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::strip)
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
