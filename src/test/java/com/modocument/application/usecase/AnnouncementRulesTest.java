package com.modocument.application.usecase;

import com.modocument.application.dto.AnnouncementRequest;
import com.modocument.application.usecase.AnnouncementRules.Draft;
import com.modocument.domain.entity.Announcement;
import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ws24 announcement validation: what the platform console may publish. */
class AnnouncementRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Instant IN_1H = NOW.plus(Duration.ofHours(1));
    private static final Instant IN_2H = NOW.plus(Duration.ofHours(2));
    private static final Instant IN_3H = NOW.plus(Duration.ofHours(3));
    private static final Instant IN_1D = NOW.plus(Duration.ofDays(1));

    @Test
    void aMinimalInfoAnnouncementStartsNowAndReachesEveryBank() {
        Draft draft = AnnouncementRules.validate(new AnnouncementRequest("info", " warning ",
                "  Planned upgrade  ", null, null, null, IN_1D, null, null, null), NOW);

        assertThat(draft.kind()).isEqualTo(AnnouncementKind.INFO);
        assertThat(draft.severity()).isEqualTo(AnnouncementSeverity.WARNING);
        assertThat(draft.title()).isEqualTo("Planned upgrade");
        assertThat(draft.body()).isEmpty();
        assertThat(draft.startsAt()).isEqualTo(NOW);
        assertThat(draft.audience()).isEmpty();

        Announcement row = new Announcement();
        draft.applyTo(row);
        assertThat(row.getAudienceBankCodes()).isNull();
    }

    @Test
    void theAudienceIsTrimmedDeduplicatedAndStoredCommaSeparated() {
        Draft draft = AnnouncementRules.validate(info(Arrays.asList(" BANK-A ", "BANK_B", "",
                null, "BANK-A")), NOW);
        assertThat(draft.audience()).containsExactly("BANK-A", "BANK_B");
        Announcement row = new Announcement();
        draft.applyTo(row);
        assertThat(row.getAudienceBankCodes()).isEqualTo("BANK-A,BANK_B");
    }

    @Test
    void aMaintenanceWindowMustLieInsideTheDisplayWindow() {
        Draft ok = AnnouncementRules.validate(maintenance(NOW, IN_3H, IN_1H, IN_2H), NOW);
        assertThat(ok.maintenanceStartsAt()).isEqualTo(IN_1H);
        assertThat(ok.maintenanceEndsAt()).isEqualTo(IN_2H);

        assertInvalid(maintenance(NOW, IN_3H, null, IN_2H), "maintenance window");
        assertInvalid(maintenance(NOW, IN_3H, IN_2H, IN_1H), "must end after it starts");
        assertInvalid(maintenance(IN_1H, IN_3H, NOW, IN_2H), "inside the display window");
        assertInvalid(maintenance(NOW, IN_2H, IN_1H, IN_3H), "inside the display window");
        // Other kinds carry no maintenance window.
        assertInvalid(new AnnouncementRequest("INFO", "INFO", "t", "", null, NOW, IN_3H,
                IN_1H, IN_2H, null), "Only a MAINTENANCE");
    }

    @Test
    void theDisplayWindowIsBoundedAndInTheFuture() {
        assertInvalid(new AnnouncementRequest("INFO", "INFO", "t", "", null, NOW, null,
                null, null, null), "endsAt");
        assertInvalid(new AnnouncementRequest("INFO", "INFO", "t", "", null, IN_2H, IN_1H,
                null, null, null), "must end after it starts");
        assertInvalid(new AnnouncementRequest("INFO", "INFO", "t", "", null,
                NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)), null, null, null),
                "already ended");
        assertInvalid(new AnnouncementRequest("INFO", "INFO", "t", "", null, NOW,
                NOW.plus(Duration.ofDays(400)), null, null, null), "at most 366 days");
    }

    @Test
    void releaseAnnouncementsNameTheirVersionAndOnlyThey() {
        Draft release = AnnouncementRules.validate(new AnnouncementRequest("RELEASE", "INFO",
                "1.1.0 is live", "", null, NOW, IN_1D, null, null, "1.1.0"), NOW);
        assertThat(release.releaseVersion()).isEqualTo("1.1.0");

        assertInvalid(new AnnouncementRequest("RELEASE", "INFO", "t", "", null, NOW, IN_1D,
                null, null, null), "release version");
        assertInvalid(new AnnouncementRequest("RELEASE", "INFO", "t", "", null, NOW, IN_1D,
                null, null, "latest"), "release version");
        assertInvalid(new AnnouncementRequest("INFO", "INFO", "t", "", null, NOW, IN_1D,
                null, null, "1.1.0"), "Only a RELEASE");
    }

    @Test
    void textIsPlainBoundedAndFreeOfControlAndDirectionCharacters() {
        assertInvalid(request("", ""), "title is required");
        assertInvalid(request("x".repeat(Announcement.TITLE_MAX + 1), ""), "title is longer");
        assertInvalid(request("t", "x".repeat(Announcement.BODY_MAX + 1)), "body is longer");
        assertInvalid(request("two\nlines", ""), "plain text on one line");
        assertInvalid(request("bell\u0007", ""), "plain text");
        assertInvalid(request("t", "evil ‮txt.exe"), "plain text");
        assertInvalid(request("⁦isolate", ""), "plain text");

        // Line breaks and tabs are fine in a body; CRLF is normalised. Markup
        // is just text (rendered escaped by the SPA) — not rejected, not mangled.
        Draft draft = AnnouncementRules.validate(request("Heads up",
                "Line one\r\n\tLine two <script>alert(1)</script>"), NOW);
        assertThat(draft.body()).isEqualTo("Line one\n\tLine two <script>alert(1)</script>");
        assertThat(AnnouncementRules.validate(request("t", "x".repeat(Announcement.BODY_MAX)),
                NOW).body()).hasSize(Announcement.BODY_MAX);
    }

    @Test
    void kindSeverityAndBankCodesAreClosedVocabularies() {
        assertInvalid(new AnnouncementRequest(null, "INFO", "t", "", null, NOW, IN_1D,
                null, null, null), "kind is required");
        assertInvalid(new AnnouncementRequest("ALERT", "INFO", "t", "", null, NOW, IN_1D,
                null, null, null), "allowed: INFO, MAINTENANCE, RELEASE");
        assertInvalid(new AnnouncementRequest("INFO", "LOUD", "t", "", null, NOW, IN_1D,
                null, null, null), "allowed: INFO, WARNING, CRITICAL");
        assertInvalid(info(List.of("bank code with spaces")), "not a bank code");
        assertInvalid(info(List.of("X".repeat(41))), "not a bank code");

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= AnnouncementRules.MAX_AUDIENCE; i++) {
            tooMany.add("B" + i);
        }
        assertInvalid(info(tooMany), "at most " + AnnouncementRules.MAX_AUDIENCE);
        assertInvalid(null, "missing");
    }

    private static AnnouncementRequest request(String title, String body) {
        return new AnnouncementRequest("INFO", "INFO", title, body, null, NOW, IN_1D,
                null, null, null);
    }

    private static AnnouncementRequest info(List<String> audience) {
        return new AnnouncementRequest("INFO", "INFO", "Notice", "body", audience, NOW, IN_1D,
                null, null, null);
    }

    private static AnnouncementRequest maintenance(Instant startsAt, Instant endsAt,
                                                   Instant windowStart, Instant windowEnd) {
        return new AnnouncementRequest("MAINTENANCE", "WARNING", "Maintenance", "Down for 1h",
                null, startsAt, endsAt, windowStart, windowEnd, null);
    }

    private static void assertInvalid(AnnouncementRequest request, String messagePart) {
        assertThatThrownBy(() -> AnnouncementRules.validate(request, NOW))
                .isInstanceOf(AnnouncementValidationException.class)
                .hasMessageContaining(messagePart);
    }
}
