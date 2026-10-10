package com.modocument.infrastructure.integration.engine;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Cron handling of SCHEDULE flows (ws25). Expressions are Spring
 * {@link CronExpression}s (6 fields: second minute hour day month weekday),
 * evaluated in UTC; a classic 5-field expression is accepted too and gets a
 * leading {@code 0} second. An expression that ever fires twice within the
 * bank's {@code integration.minScheduleInterval} is refused — the gaps of
 * the next 8 days (at most 2,000 slots) are checked, which covers every
 * minute/hour/day/weekday pattern.
 */
public final class FlowSchedule {

    private static final int SCAN_SLOTS = 2_000;
    private static final Duration SCAN_SPAN = Duration.ofDays(8);

    private FlowSchedule() {
    }

    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("A SCHEDULE flow needs a cron expression.");
        }
        String trimmed = expression.trim();
        if (trimmed.length() > 120) {
            throw new IllegalArgumentException("The cron expression is too long.");
        }
        String normalized = trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The cron expression is not valid: "
                    + e.getMessage());
        }
    }

    /** Refuses an expression that fires more often than {@code minimum}. */
    public static void requireMinimumInterval(String expression, Duration minimum, Instant from) {
        CronExpression cron = parse(expression);
        ZonedDateTime previous = cron.next(from.atZone(ZoneOffset.UTC));
        if (previous == null) {
            throw new IllegalArgumentException("The cron expression never fires.");
        }
        ZonedDateTime end = previous.plus(SCAN_SPAN);
        for (int i = 0; i < SCAN_SLOTS; i++) {
            ZonedDateTime next = cron.next(previous);
            if (next == null || next.isAfter(end)) {
                return;
            }
            if (Duration.between(previous, next).compareTo(minimum) < 0) {
                throw new IllegalArgumentException("The schedule fires more often than the "
                        + "bank's minimum interval of " + minimum.toMinutes() + " minutes.");
            }
            previous = next;
        }
    }

    /** The first slot strictly after {@code after}, or null when the expression never fires. */
    public static Instant next(String expression, Instant after) {
        ZonedDateTime next = parse(expression).next(after.atZone(ZoneOffset.UTC));
        return next == null ? null : next.toInstant();
    }
}
