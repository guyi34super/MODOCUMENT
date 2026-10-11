package com.modocument.application.usecase;

import com.modocument.application.dto.AnnouncementRequest;
import com.modocument.application.dto.AnnouncementView;
import com.modocument.application.dto.PlatformAnnouncementView;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.usecase.AnnouncementRules.Draft;
import com.modocument.domain.entity.Announcement;
import com.modocument.domain.model.AnnouncementKind;
import com.modocument.domain.model.AnnouncementSeverity;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.persistence.AnnouncementJpaRepository;
import com.modocument.infrastructure.release.AnnouncementCache;
import com.modocument.infrastructure.release.LiveAnnouncement;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Announcements (ws24, docs/phase2/contracts.md §3).
 *
 * <p><b>Bank users</b> — {@link #activeFor(int)} behind
 * {@code GET /api/announcements/active} (PROFILE_SELF): the live
 * announcements from {@link AnnouncementCache} (no database read per
 * request), filtered to the caller's bank code and the current window,
 * newest start first; MAINTENANCE is never dismissible.
 *
 * <p><b>Platform console</b> — list (PLATFORM_READ), create/update/archive
 * (PLATFORM_ANNOUNCE). The permission checker already pins those to callers
 * in the platform bank, so the request's tenant IS the platform bank: rows
 * are written with that BankId under the normal request transaction, and row
 * level security keeps them away from every customer tenant. Every write is
 * audited ({@value #PUBLISHED} for create and update, {@value #ARCHIVED} for
 * delete) and marks the cache stale after the commit.
 *
 * <p><b>Deployment recorder</b> — {@link #publishRelease} writes the RELEASE
 * banner of a new version, called as the platform bank (TenantContext.runAs)
 * in its own transaction.
 */
@Service
public class AnnouncementUseCase {

    /** Audit action of a created or updated announcement (contracts §5). */
    public static final String PUBLISHED = "ANNOUNCEMENT_PUBLISHED";

    /** Audit action of an archived (deleted) announcement. */
    public static final String ARCHIVED = "ANNOUNCEMENT_ARCHIVED";

    static final String ENTITY = "Announcement";

    /** Rows the platform console lists (newest first). */
    static final int CONSOLE_LIMIT = 200;

    private static final Comparator<LiveAnnouncement> NEWEST_FIRST =
            Comparator.comparing(LiveAnnouncement::startsAt).reversed()
                    .thenComparing(Comparator.comparingInt(LiveAnnouncement::id).reversed());

    private final AnnouncementJpaRepository announcements;
    private final AnnouncementCache cache;
    private final BankDirectory banks;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;
    private final Clock clock;

    public AnnouncementUseCase(AnnouncementJpaRepository announcements, AnnouncementCache cache,
                               BankDirectory banks, CurrentUserProvider currentUser,
                               AuditRecorder audit) {
        this.announcements = announcements;
        this.cache = cache;
        this.banks = banks;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = Clock.systemUTC();
    }

    // ------------------------------------------------------------ bank users

    /** The caller's banners: in window, for the caller's bank, newest first. */
    public List<AnnouncementView> activeFor(int bankId) {
        String bankCode = banks.find(bankId).map(BankDirectory.BankRef::code).orElse(null);
        Instant now = clock.instant();
        return cache.live().stream()
                .filter(a -> a.activeAt(now))
                .filter(a -> a.visibleTo(bankCode))
                .sorted(NEWEST_FIRST)
                .map(AnnouncementUseCase::toView)
                .toList();
    }

    // ------------------------------------------------------- platform console

    @Transactional(readOnly = true)
    public List<PlatformAnnouncementView> listForConsole() {
        int bankId = currentUser.requireBankId();
        Instant now = clock.instant();
        return announcements.findForConsole(bankId, PageRequest.of(0, CONSOLE_LIMIT)).stream()
                .map(row -> toConsoleView(row, now))
                .toList();
    }

    @Transactional
    public PlatformAnnouncementView create(AnnouncementRequest request) {
        Instant now = clock.instant();
        Draft draft = AnnouncementRules.validate(request, now);
        Announcement row = new Announcement();
        row.setBankId(currentUser.requireBankId());
        draft.applyTo(row);
        row.setCreatedBy(currentUser.requireUserId());
        row.setCreatedAt(now);
        Announcement saved = announcements.save(row);
        audit.success(PUBLISHED, ENTITY, String.valueOf(saved.getAnnouncementId()),
                null, snapshot(saved));
        invalidateCacheAfterCommit();
        return toConsoleView(saved, now);
    }

    @Transactional
    public PlatformAnnouncementView update(int id, AnnouncementRequest request) {
        Instant now = clock.instant();
        Announcement row = owned(id);
        if (row.isArchived()) {
            throw new IllegalStateException("Announcement " + id + " is archived; create a new one.");
        }
        Draft draft = AnnouncementRules.validate(request, now);
        Map<String, Object> before = snapshot(row);
        draft.applyTo(row);
        row.setUpdatedBy(currentUser.requireUserId());
        row.setUpdatedAt(now);
        Announcement saved = announcements.save(row);
        audit.success(PUBLISHED, ENTITY, String.valueOf(id), before, snapshot(saved));
        invalidateCacheAfterCommit();
        return toConsoleView(saved, now);
    }

    /** Archives (never deletes: the row is the record of what users were told). */
    @Transactional
    public void archive(int id) {
        Announcement row = owned(id);
        if (row.isArchived()) {
            return;
        }
        Instant now = clock.instant();
        row.setArchived(true);
        row.setUpdatedBy(currentUser.requireUserId());
        row.setUpdatedAt(now);
        announcements.save(row);
        Map<String, Object> archived = new LinkedHashMap<>();
        archived.put("title", row.getTitle());
        archived.put("kind", row.getKind());
        audit.success(ARCHIVED, ENTITY, String.valueOf(id), null, archived);
        invalidateCacheAfterCommit();
    }

    // ------------------------------------------------------ deployment recorder

    /**
     * The RELEASE banner of a newly deployed version, owned by the platform
     * bank (the caller binds it: {@code TenantContext.runAs(platformBankId)}),
     * shown to every bank for {@code showFor}. Skipped when a live RELEASE
     * banner for this version already exists (a platform admin wrote one).
     *
     * @return the new announcement's id, or empty when skipped
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> publishRelease(int platformBankId, String version, Duration showFor) {
        Instant now = clock.instant();
        if (announcements.existsByBankIdAndKindAndReleaseVersionAndArchivedFalse(
                platformBankId, AnnouncementKind.RELEASE, version)) {
            return Optional.empty();
        }
        Draft draft = AnnouncementRules.validate(new AnnouncementRequest(
                AnnouncementKind.RELEASE.name(), AnnouncementSeverity.INFO.name(),
                "MoDocument " + version + " is now live",
                "A new version of MoDocument has been deployed. Reload open pages to use it.",
                null, now, now.plus(showFor), null, null, version), now);
        Announcement row = new Announcement();
        row.setBankId(platformBankId);
        draft.applyTo(row);
        row.setCreatedAt(now);
        Announcement saved = announcements.save(row);
        audit.record(AuditEventSpec.builder(PUBLISHED, AuditOutcome.SUCCESS)
                .entity(ENTITY, String.valueOf(saved.getAnnouncementId()))
                .bankId(platformBankId)
                .newValues(snapshot(saved))
                .build());
        invalidateCacheAfterCommit();
        return Optional.of(saved.getAnnouncementId());
    }

    // ------------------------------------------------------------------ helpers

    /** The caller's bank's row (the platform bank); anything else is a 404. */
    private Announcement owned(int id) {
        return announcements.findByAnnouncementIdAndBankId(id, currentUser.requireBankId())
                .orElseThrow(() -> new NoSuchElementException("Announcement " + id + " not found."));
    }

    private void invalidateCacheAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidate();
                }
            });
        } else {
            cache.invalidate();
        }
    }

    private static AnnouncementView toView(LiveAnnouncement a) {
        return new AnnouncementView(a.id(), a.kind(), a.severity(), a.title(), a.body(),
                a.startsAt(), a.endsAt(), a.maintenanceStartsAt(), a.maintenanceEndsAt(),
                a.releaseVersion(), a.kind().dismissible());
    }

    static String status(Announcement row, Instant now) {
        if (row.isArchived()) {
            return "ARCHIVED";
        }
        if (now.isBefore(row.getStartsAt())) {
            return "SCHEDULED";
        }
        return now.isBefore(row.getEndsAt()) ? "ACTIVE" : "ENDED";
    }

    private static PlatformAnnouncementView toConsoleView(Announcement row, Instant now) {
        List<String> audience = new ArrayList<>(LiveAnnouncement.parseAudience(row.getAudienceBankCodes()));
        audience.sort(null);
        return new PlatformAnnouncementView(row.getAnnouncementId(), row.getKind(),
                row.getSeverity(), row.getTitle(), row.getBody(),
                audience.isEmpty() ? null : List.copyOf(audience),
                row.getStartsAt(), row.getEndsAt(),
                row.getMaintenanceStartsAt(), row.getMaintenanceEndsAt(),
                row.getReleaseVersion(), row.isArchived(), status(row, now),
                row.getCreatedBy(), row.getCreatedAt(), row.getUpdatedBy(), row.getUpdatedAt());
    }

    /** What the audit row records: exactly what users were (or will be) shown. */
    private static Map<String, Object> snapshot(Announcement row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", row.getKind());
        values.put("severity", row.getSeverity());
        values.put("title", row.getTitle());
        values.put("body", row.getBody());
        values.put("audienceBankCodes", row.getAudienceBankCodes());
        values.put("startsAt", String.valueOf(row.getStartsAt()));
        values.put("endsAt", String.valueOf(row.getEndsAt()));
        if (row.getMaintenanceStartsAt() != null) {
            values.put("maintenanceStartsAt", row.getMaintenanceStartsAt().toString());
            values.put("maintenanceEndsAt", String.valueOf(row.getMaintenanceEndsAt()));
        }
        if (row.getReleaseVersion() != null) {
            values.put("releaseVersion", row.getReleaseVersion());
        }
        return values;
    }
}
