package com.modocument.application.usecase;

import com.modocument.application.dto.AnnouncementRequest;
import com.modocument.domain.entity.Announcement;
import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and normalisation of an announcement (ws24) — one place for
 * the platform console's POST/PUT and the deployment recorder's RELEASE
 * banner. Everything a bank user will read is checked here: plain text only
 * (no control characters, no bidirectional overrides that could disguise
 * the text), bounded lengths, a sane window and, for MAINTENANCE, an outage
 * window inside it.
 */
public final class AnnouncementRules {

    /** Longest display window. */
    static final Duration MAX_WINDOW = Duration.ofDays(366);

    /** Most banks one announcement may target by code. */
    static final int MAX_AUDIENCE = 100;

    private static final Pattern BANK_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    private static final Pattern RELEASE_VERSION =
            Pattern.compile("\\d{1,4}\\.\\d{1,4}\\.\\d{1,6}(?:-[0-9A-Za-z.-]{1,30})?");

    private AnnouncementRules() {
    }

    /** A validated, normalised announcement, ready to be written. */
    public record Draft(AnnouncementKind kind, AnnouncementSeverity severity, String title,
                        String body, List<String> audience, Instant startsAt, Instant endsAt,
                        Instant maintenanceStartsAt, Instant maintenanceEndsAt,
                        String releaseVersion) {

        /** Copies the draft onto a (new or existing) row. */
        public void applyTo(Announcement row) {
            row.setKind(kind);
            row.setSeverity(severity);
            row.setTitle(title);
            row.setBody(body);
            row.setAudienceBankCodes(audience.isEmpty() ? null : String.join(",", audience));
            row.setStartsAt(startsAt);
            row.setEndsAt(endsAt);
            row.setMaintenanceStartsAt(maintenanceStartsAt);
            row.setMaintenanceEndsAt(maintenanceEndsAt);
            row.setReleaseVersion(releaseVersion);
        }
    }

    /**
     * @param now default start when the request names none
     * @throws AnnouncementValidationException with a message fit for the console
     */
    public static Draft validate(AnnouncementRequest request, Instant now) {
        if (request == null) {
            throw invalid("The announcement is missing.");
        }
        AnnouncementKind kind = parse(AnnouncementKind.class, request.kind(), "kind");
        AnnouncementSeverity severity = parse(AnnouncementSeverity.class, request.severity(),
                "severity");

        String title = request.title() == null ? "" : request.title().strip();
        if (title.isEmpty()) {
            throw invalid("The title is required.");
        }
        if (title.length() > Announcement.TITLE_MAX) {
            throw invalid("The title is longer than " + Announcement.TITLE_MAX + " characters.");
        }
        if (!plainText(title, false)) {
            throw invalid("The title must be plain text on one line (no control or "
                    + "text-direction characters).");
        }

        String body = request.body() == null ? "" : request.body().replace("\r\n", "\n").strip();
        if (body.length() > Announcement.BODY_MAX) {
            throw invalid("The body is longer than " + Announcement.BODY_MAX + " characters.");
        }
        if (!plainText(body, true)) {
            throw invalid("The body must be plain text (line breaks are fine; no other control "
                    + "or text-direction characters).");
        }

        // Whole seconds: what is stored is exactly what is served back
        // (timestamptz keeps microseconds, Instant.now() has nanoseconds).
        Instant startsAt = seconds(request.startsAt() == null ? now : request.startsAt());
        Instant endsAt = seconds(request.endsAt());
        if (endsAt == null) {
            throw invalid("The end of the display window (endsAt) is required.");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw invalid("The display window must end after it starts.");
        }
        if (!endsAt.isAfter(now)) {
            throw invalid("The display window has already ended.");
        }
        if (Duration.between(startsAt, endsAt).compareTo(MAX_WINDOW) > 0) {
            throw invalid("The display window may last at most " + MAX_WINDOW.toDays() + " days.");
        }

        Instant maintenanceStartsAt = seconds(request.maintenanceStartsAt());
        Instant maintenanceEndsAt = seconds(request.maintenanceEndsAt());
        if (kind == AnnouncementKind.MAINTENANCE) {
            if (maintenanceStartsAt == null || maintenanceEndsAt == null) {
                throw invalid("A maintenance announcement needs the maintenance window "
                        + "(maintenanceStartsAt and maintenanceEndsAt).");
            }
            if (!maintenanceEndsAt.isAfter(maintenanceStartsAt)) {
                throw invalid("The maintenance window must end after it starts.");
            }
            if (maintenanceStartsAt.isBefore(startsAt) || maintenanceEndsAt.isAfter(endsAt)) {
                throw invalid("The maintenance window must lie inside the display window "
                        + "(announce it before it starts, keep it shown until it ends).");
            }
        } else if (maintenanceStartsAt != null || maintenanceEndsAt != null) {
            throw invalid("Only a MAINTENANCE announcement has a maintenance window.");
        }

        String releaseVersion = request.releaseVersion() == null
                ? null : request.releaseVersion().strip();
        if (releaseVersion != null && releaseVersion.isEmpty()) {
            releaseVersion = null;
        }
        if (kind == AnnouncementKind.RELEASE) {
            if (releaseVersion == null || !RELEASE_VERSION.matcher(releaseVersion).matches()) {
                throw invalid("A RELEASE announcement needs the release version (X.Y.Z).");
            }
        } else if (releaseVersion != null) {
            throw invalid("Only a RELEASE announcement names a release version.");
        }

        return new Draft(kind, severity, title, body, audience(request.audienceBankCodes()),
                startsAt, endsAt, maintenanceStartsAt, maintenanceEndsAt, releaseVersion);
    }

    private static List<String> audience(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            String trimmed = code == null ? "" : code.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!BANK_CODE.matcher(trimmed).matches()) {
                throw invalid("'" + printable(trimmed) + "' is not a bank code (letters, digits, "
                        + ". _ -, at most 40).");
            }
            unique.add(trimmed);
        }
        if (unique.size() > MAX_AUDIENCE) {
            throw invalid("An announcement may target at most " + MAX_AUDIENCE
                    + " banks; leave the audience empty to reach every bank.");
        }
        return List.copyOf(unique);
    }

    /**
     * Plain text: no C0/C1 control characters (line feed and tab allowed in
     * a body) and no Unicode bidirectional embedding/override/isolate marks,
     * which could make a banner read differently from what was approved.
     */
    static boolean plainText(String text, boolean multiline) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (multiline && (c == '\n' || c == '\t')) {
                continue;
            }
            if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
                return false;
            }
            if ((c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩')
                    || c == '‎' || c == '‏' || c == '؜') {
                return false;
            }
        }
        return true;
    }

    private static Instant seconds(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("The " + field + " is required.");
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                allowed.add(constant.name());
            }
            throw invalid("Unknown " + field + " '" + printable(value) + "' (allowed: "
                    + String.join(", ", allowed) + ").");
        }
    }

    /** Echo of user input in an error: bounded, control characters replaced. */
    private static String printable(String value) {
        String bounded = value.length() > 40 ? value.substring(0, 40) + "…" : value;
        StringBuilder out = new StringBuilder(bounded.length());
        for (int i = 0; i < bounded.length(); i++) {
            char c = bounded.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }

    private static AnnouncementValidationException invalid(String message) {
        return new AnnouncementValidationException(message);
    }
}
