package com.modocument.infrastructure.release;

import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.port.TenantContext;
import com.modocument.infrastructure.persistence.AnnouncementJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory copy of the live announcements (ws24) behind
 * {@code GET /api/announcements/active}, which every signed-in page load
 * calls — so the endpoint never touches the database per request.
 *
 * <p>What is held: the platform bank's announcements that are not archived
 * and have not ended (scheduled ones included, so they switch on at their
 * start without a reload), at most {@value #MAX_LIVE}. The caller filters by
 * window and audience at request time.
 *
 * <p>Freshness: reloaded when older than {@code modocument.announcements
 * .cache-ttl} (default 30 s) — the bound for a change made on ANOTHER
 * instance — and marked stale at once by {@link #invalidate()} after a write
 * on this one. One thread reloads; concurrent readers keep the previous
 * snapshot meanwhile (only the very first load makes them wait). A failed
 * reload keeps the previous copy and is retried after the TTL.
 *
 * <p>Row level security: the rows belong to the platform bank, invisible to
 * every customer tenant. The reload therefore reads AS the platform bank —
 * {@code TenantContext.runAs(platform bank)} in its own read-only
 * transaction, a registered call site (see {@link TenantContext}) — never as
 * SYSTEM: it can read exactly the platform's rows and nothing else. No
 * platform bank yet means no announcements.
 */
@Component
public class AnnouncementCache {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementCache.class);

    /** Cap on the working set (live + scheduled); the newest start wins. */
    static final int MAX_LIVE = 200;

    private final AnnouncementJpaRepository announcements;
    private final PlatformBankLookup platformBank;
    private final TransactionTemplate readOnlyNewTx;
    private final long ttlNanos;
    private final Clock clock;
    private final ReentrantLock reloadLock = new ReentrantLock();
    private final AtomicLong generation = new AtomicLong();

    private volatile Snapshot snapshot;

    @Autowired
    public AnnouncementCache(AnnouncementJpaRepository announcements,
                             PlatformBankLookup platformBank,
                             PlatformTransactionManager transactionManager,
                             @Value("${modocument.announcements.cache-ttl:30s}") Duration ttl) {
        this(announcements, platformBank, transactionManager, ttl, Clock.systemUTC());
    }

    AnnouncementCache(AnnouncementJpaRepository announcements, PlatformBankLookup platformBank,
                      PlatformTransactionManager transactionManager, Duration ttl, Clock clock) {
        this.announcements = announcements;
        this.platformBank = platformBank;
        this.ttlNanos = ttl.toNanos();
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // A NEW transaction begun under runAs(platform bank): the tenant is
        // bound at transaction begin, so joining the caller's (customer
        // bank) transaction would read nothing under RLS.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        this.readOnlyNewTx = template;
    }

    /** The live and scheduled announcements, newest start first. */
    public List<LiveAnnouncement> live() {
        Snapshot current = snapshot;
        if (fresh(current)) {
            return current.items();
        }
        if (current != null) {
            // Stale: one thread reloads, the others keep serving the old copy.
            if (!reloadLock.tryLock()) {
                return current.items();
            }
        } else {
            reloadLock.lock();
        }
        try {
            Snapshot latest = snapshot;
            if (fresh(latest)) {
                return latest.items();
            }
            Snapshot loaded = load(latest, generation.get());
            snapshot = loaded;
            return loaded.items();
        } finally {
            reloadLock.unlock();
        }
    }

    /**
     * Marks the snapshot stale; the next {@link #live()} reads the database.
     * A reload already running when this is called does not count as fresh
     * afterwards (it may have read before the caller's commit).
     */
    public void invalidate() {
        generation.incrementAndGet();
    }

    private boolean fresh(Snapshot candidate) {
        return candidate != null
                && candidate.generation() == generation.get()
                && System.nanoTime() - candidate.loadedAtNanos() < ttlNanos;
    }

    private Snapshot load(Snapshot previous, long loadGeneration) {
        long started = System.nanoTime();
        try {
            OptionalInt platform = platformBank.platformBankId();
            if (platform.isEmpty()) {
                return new Snapshot(List.of(), started, loadGeneration);
            }
            int bankId = platform.getAsInt();
            List<LiveAnnouncement> items = TenantContext.runAs(bankId, () ->
                    readOnlyNewTx.execute(status -> announcements
                            .findLive(bankId, clock.instant(), PageRequest.of(0, MAX_LIVE))
                            .stream()
                            .map(LiveAnnouncement::of)
                            .toList()));
            return new Snapshot(items == null ? List.of() : items, started, loadGeneration);
        } catch (Exception e) {
            // Banners are informational: a failed reload must never break the
            // page. Keep the previous copy (or nothing) and retry after the TTL.
            log.warn("Announcements could not be loaded ({}); serving the previous copy.",
                    e.getClass().getSimpleName());
            return new Snapshot(previous == null ? List.of() : previous.items(), started,
                    loadGeneration);
        }
    }

    private record Snapshot(List<LiveAnnouncement> items, long loadedAtNanos, long generation) {
    }
}
