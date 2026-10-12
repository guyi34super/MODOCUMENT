package com.modocument.infrastructure.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory windows behind the recorder's flood control (ws28):
 *
 * <ul>
 *   <li><b>Aggregation</b> ({@link #offerDenial}) — ACCESS_DENIED: the
 *       FIRST denial of a key is written at once with {@code count = 1} and
 *       opens a one-minute window; later denials of that key inside the
 *       window are only counted. The suppressed count is written as ONE row
 *       when the key is denied again after the window (that row carries
 *       {@code suppressed + 1}) or by the periodic {@link #drainExpired}
 *       (that row carries {@code suppressed}); each such row opens the next
 *       window. So a key never gets more than one row per minute, and the
 *       sum of {@code count} over its rows is the number of denials.</li>
 *   <li><b>Deduplication</b> ({@link #tryAcquire}) — read audits: the first
 *       event of a key per window is written, the rest are dropped (a read
 *       repeated within the hour adds nothing to "who looked at what").</li>
 * </ul>
 *
 * <p>Both maps are bounded: {@value #MAX_DENIAL_KEYS} aggregation keys (past
 * that, new keys share one overflow key per bank and entity type, so a
 * spray from many addresses still yields few rows) and
 * {@value #MAX_DEDUPE_KEYS} dedupe keys (past that, the expired ones are
 * purged and, if still full, everything is forgotten — which can only ADD
 * audit rows, never lose one). Per JVM: with several tasks each one
 * aggregates its own share; the documented bound is per task.
 */
public class AuditThrottle {

    static final int MAX_DENIAL_KEYS = 10_000;
    static final int MAX_DEDUPE_KEYS = 50_000;
    static final int MAX_SAMPLE_IDS = 5;
    static final String OVERFLOW_ACTOR = "*overflow*";

    private final Clock clock;
    private final Duration denialWindow;
    private final Map<DenialKey, Window> denials = new ConcurrentHashMap<>();
    private final Map<String, Instant> dedupe = new ConcurrentHashMap<>();

    public AuditThrottle(Clock clock, Duration denialWindow) {
        this.clock = clock;
        this.denialWindow = denialWindow;
    }

    /** What the aggregation groups by: bank, actor-or-ip and entity type. */
    public record DenialKey(int bankId, String actorOrIp, String entityType) {
    }

    /** The row context a flush writes with (no request is around then). */
    public record DenialContext(Integer actorUserId, String actorEmail, String actorRole,
                                String ip, String entityType, String entityId,
                                Map<String, Object> details) {
    }

    /** A row to write: the context of the window's first denial plus the count. */
    public record DenialRow(DenialKey key, DenialContext context, long count, Instant windowStart,
                            List<String> sampleEntityIds) {
    }

    /** Mutable per-key state, only ever touched inside {@code compute}. */
    private static final class Window {
        final Instant start;
        final Instant end;
        final DenialContext context;
        long suppressed;
        final Set<String> samples = new LinkedHashSet<>();

        Window(Instant start, Instant end, DenialContext context) {
            this.start = start;
            this.end = end;
            this.context = context;
        }

        void sample(String entityId) {
            if (entityId != null && samples.size() < MAX_SAMPLE_IDS) {
                samples.add(entityId);
            }
        }
    }

    /**
     * One denial. Returns the row to write now (the window's opener, with
     * any count carried over from the previous window), or null when the
     * denial is only counted.
     */
    public DenialRow offerDenial(DenialKey requested, DenialContext context) {
        Instant now = clock.instant();
        DenialKey key = requested;
        if (!denials.containsKey(key) && denials.size() >= MAX_DENIAL_KEYS) {
            evictIdleDenials(now);
            if (denials.size() >= MAX_DENIAL_KEYS) {
                key = new DenialKey(requested.bankId(), OVERFLOW_ACTOR, requested.entityType());
            }
        }
        DenialRow[] row = new DenialRow[1];
        DenialKey finalKey = key;
        denials.compute(key, (k, window) -> {
            if (window == null || !now.isBefore(window.end)) {
                long carried = window == null ? 0 : window.suppressed;
                List<String> samples = new ArrayList<>();
                if (window != null) {
                    samples.addAll(window.samples);
                }
                if (context.entityId() != null && !samples.contains(context.entityId())
                        && samples.size() < MAX_SAMPLE_IDS) {
                    samples.add(context.entityId());
                }
                row[0] = new DenialRow(finalKey, context, carried + 1,
                        window == null ? now : window.start, List.copyOf(samples));
                return new Window(now, now.plus(denialWindow), context);
            }
            window.suppressed++;
            window.sample(context.entityId());
            return window;
        });
        return row[0];
    }

    /**
     * Rows for windows that ended with suppressed denials; each such key
     * opens a fresh (empty) window so the one-row-per-window bound holds.
     * Keys whose window ended with nothing suppressed are forgotten.
     */
    public List<DenialRow> drainExpired(Instant now) {
        List<DenialRow> rows = new ArrayList<>();
        for (DenialKey key : List.copyOf(denials.keySet())) {
            denials.computeIfPresent(key, (k, window) -> {
                if (now.isBefore(window.end)) {
                    return window;
                }
                if (window.suppressed == 0) {
                    return null;
                }
                rows.add(new DenialRow(k, window.context, window.suppressed, window.start,
                        List.copyOf(window.samples)));
                return new Window(now, now.plus(denialWindow), window.context);
            });
        }
        return rows;
    }

    /** {@link #drainExpired(Instant)} at the clock's now. */
    public List<DenialRow> drainExpired() {
        return drainExpired(clock.instant());
    }

    /**
     * Whether {@code key} is free in its window — and if so, claims it for
     * {@code window}. A claim whose write then fails must be
     * {@link #release released} so the next event is written.
     */
    public boolean tryAcquire(String key, Duration window) {
        Instant now = clock.instant();
        if (dedupe.size() >= MAX_DEDUPE_KEYS) {
            dedupe.values().removeIf(expiry -> !now.isBefore(expiry));
            if (dedupe.size() >= MAX_DEDUPE_KEYS) {
                dedupe.clear();
            }
        }
        boolean[] acquired = new boolean[1];
        dedupe.compute(key, (k, expiry) -> {
            if (expiry == null || !now.isBefore(expiry)) {
                acquired[0] = true;
                return now.plus(window);
            }
            return expiry;
        });
        return acquired[0];
    }

    public void release(String key) {
        dedupe.remove(key);
    }

    /** Test seam: forgets every window. */
    void clear() {
        denials.clear();
        dedupe.clear();
    }

    private void evictIdleDenials(Instant now) {
        denials.entrySet().removeIf(e -> !now.isBefore(e.getValue().end)
                && e.getValue().suppressed == 0);
    }
}
