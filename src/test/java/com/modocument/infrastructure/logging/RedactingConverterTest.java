package com.modocument.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.modocument.application.privacy.LogSafe;
import com.modocument.application.privacy.PiiPatterns;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit matrix for the redaction pattern families (gate C3: PRIV-005 log
 * part, SEC-019), plus the two logback converters that apply them to the
 * human console pattern ({@code %mask} for the message, {@code %maskEx} for
 * the rendered stack trace).
 */
class RedactingConverterTest {

    private static final String MASK = PiiPatterns.MASK;

    // ---- Mauritius NIC family ---------------------------------------------

    @Test
    void masksNicLetterPlusThirteenDigits() {
        assertThat(LogSafe.scrub("rejected NIC A1234567890123 in row 4"))
                .isEqualTo("rejected NIC " + MASK + " in row 4");
    }

    @Test
    void masksModernNicWithCheckLetter() {
        assertThat(LogSafe.scrub("holder M090976290148E flagged"))
                .doesNotContain("M090976290148E").contains(MASK);
    }

    @Test
    void masksShortLetterPrefixedIds() {
        // the 10-12 character letter-prefixed forms
        assertThat(LogSafe.scrub("ids K1234567890 and b123456789012"))
                .doesNotContain("K1234567890").doesNotContain("b123456789012");
    }

    // ---- account-number runs ----------------------------------------------

    @Test
    void masksNineOrMoreDigitRuns() {
        assertThat(LogSafe.scrub("account 123456789 then 12345678901234."))
                .isEqualTo("account " + MASK + " then " + MASK + ".");
    }

    @Test
    void keepsShortNumbersJobIdsAndCounts() {
        String line = "Ingestion job 42 failed during PARSED stage for bank 7 (12345678 rows)";
        assertThat(LogSafe.scrub(line)).isEqualTo(line);
    }

    // ---- emails -------------------------------------------------------------

    @Test
    void masksEmailLocalPartButKeepsDomain() {
        assertThat(LogSafe.scrub("user jane.doe@dayforce.com locked out"))
                .isEqualTo("user j***@dayforce.com locked out");
    }

    // ---- JWTs and Bearer headers --------------------------------------------

    @Test
    void masksJwtLookingStrings() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYW5hcnkifQ.c2lnbmF0dXJl";
        assertThat(LogSafe.scrub("got token " + jwt + " back"))
                .doesNotContain(jwt).doesNotContain("eyJ");
    }

    @Test
    void masksBearerHeaderValueEntirely() {
        // Standalone scheme + token: the scheme survives, the token does not.
        assertThat(LogSafe.scrub("sent Bearer abc.DEF-123_456= upstream"))
                .isEqualTo("sent Bearer " + MASK + " upstream");
        // Full Authorization header: the authorization: key=value pass also
        // masks what is left after the scheme — fail-closed, nothing leaks.
        assertThat(LogSafe.scrub("header Authorization: Bearer abc.DEF-123_456= rest"))
                .doesNotContain("abc.DEF-123_456=").contains(MASK);
    }

    // ---- credential key=value shapes ----------------------------------------

    @Test
    void masksPasswordTokenAndSecretAssignments() {
        assertThat(LogSafe.scrub("login failed password=CanaryPass123!x"))
                .isEqualTo("login failed password=" + MASK);
        assertThat(LogSafe.scrub("retry with token: abc123DEF"))
                .isEqualTo("retry with token: " + MASK);
        assertThat(LogSafe.scrub("config client_secret=shhh api-key=k-123"))
                .doesNotContain("shhh").doesNotContain("k-123");
        assertThat(LogSafe.scrub("body {\"password\":\"hunter2\"}"))
                .doesNotContain("hunter2");
    }

    @Test
    void masksRefreshCookieValue() {
        assertThat(LogSafe.scrub("Cookie: modoc_refresh=Ab3_x-Yz0123456789AbCdEfGhIjKlMnOpQrStUvW; x=1"))
                .doesNotContain("Ab3_x-Yz0123456789AbCdEfGhIjKlMnOpQrStUvW")
                .contains("modoc_refresh=" + MASK);
    }

    @Test
    void keepsTokenCountsAndPluralKeywords() {
        String line = "prompt used 1500 tokens";
        assertThat(LogSafe.scrub(line)).isEqualTo(line);
    }

    // ---- what must survive (log utility, E9) ---------------------------------

    @Test
    void keepsUuidsRequestIdsAndTimestamps() {
        String line = "requestId 0199c2f4-7d3a-7b5c-8a3e-4f2b1c9d0e7a "
                + "at 2026-10-04T10:15:30.123456789Z user 17 bank 3";
        assertThat(LogSafe.scrub(line)).isEqualTo(line);
    }

    @Test
    void scrubIsNullSafeAndIdempotent() {
        assertThat(LogSafe.scrub(null)).isNull();
        assertThat(LogSafe.scrub("")).isEmpty();
        String once = LogSafe.scrub("NIC A1234567890123 password=x1");
        assertThat(LogSafe.scrub(once)).isEqualTo(once);
    }

    // ---- the logback converters themselves ------------------------------------

    @Test
    void messageConverterScrubsFormattedMessage() {
        LoggerContext context = new LoggerContext();
        LoggingEvent event = new LoggingEvent("x", context.getLogger("test"), Level.INFO,
                "parse failed for NIC {} account {}", null,
                new Object[]{"A1234567890123", "12345678901234"});

        RedactingConverter converter = new RedactingConverter();
        String rendered = converter.convert(event);

        assertThat(rendered)
                .doesNotContain("A1234567890123")
                .doesNotContain("12345678901234")
                .contains(MASK)
                .contains("parse failed for NIC");
    }

    @Test
    void throwableConverterScrubsExceptionMessageInsideStackTrace() {
        LoggerContext context = new LoggerContext();
        IllegalStateException boom = new IllegalStateException(
                "insert failed: VALUES ('A1234567890123', 'canary@example.test')");
        LoggingEvent event = new LoggingEvent("x", context.getLogger("test"), Level.ERROR,
                "Unhandled failure", boom, null);

        RedactingThrowableConverter converter = new RedactingThrowableConverter();
        converter.setContext(context);
        converter.start();
        String rendered = converter.convert((ILoggingEvent) event);

        assertThat(rendered)
                .doesNotContain("A1234567890123")
                .doesNotContain("canary@example.test")
                .contains(MASK)
                // ops utility: exception type and frames survive
                .contains("IllegalStateException")
                .contains("RedactingConverterTest");
    }
}
