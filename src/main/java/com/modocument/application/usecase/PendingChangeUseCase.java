package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.security.PermissionChecker;
import com.modocument.application.dto.PendingChangeDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.PlatformBankLookup;
import com.modocument.application.security.ChangeAudience;
import com.modocument.application.security.SodPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.PendingChangeStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Maker/checker dual control over critical changes (ws10, gate B7,
 * SEC-023). A maker SUBMITS a change (same permission the direct operation
 * required — enforced here per type, so the generic endpoint cannot widen
 * anything); the change sits REQUESTED until a user holding
 * {@link Permission#CHANGE_APPROVE} decides it.
 *
 * <p><b>The B7 invariant:</b> {@link #approve} hard-refuses
 * checker == maker with a 409 and a SELF_APPROVAL_BLOCKED (DENIED) audit
 * row — written in its own transaction, so the evidence survives the
 * refusal. Approval by a DIFFERENT user executes the type's registered
 * {@link PendingChangeApplier} inside the same transaction (the appliers
 * re-run every ws09 invariant: bank scoping, self-targeting, last-admin
 * guard) and the row becomes APPLIED; an applier failure rolls the domain
 * change back and marks the row FAILED in an independent transaction.
 *
 * <p>Bank scoping: rows are RLS-covered on PostgreSQL, and every read here
 * filters by the caller's bank anyway (H2 tests + defense in depth);
 * because AppUsers is NOT under RLS, {@link #approve} additionally asserts
 * maker and checker belong to the same bank.
 *
 * <p><b>Who decides</b> ({@link ChangeAudience}, every change type placed):
 * a bank type is decided by any other CHANGE_APPROVE holder of the maker's
 * bank; a platform type (platform settings, bank lifecycle, egress
 * approvals) only by a second platform admin — CHANGE_APPROVE plus the
 * type's platform permission, in the platform bank. {@link #list} shows a
 * caller exactly the rows they may decide plus their own submissions;
 * {@link #approve}/{@link #reject} refuse (403) a checker outside the
 * type's audience.
 */
@Service
public class PendingChangeUseCase {

    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final PendingChangeRepository changes;
    private final AuditRecorder audit;
    private final PermissionChecker perm;
    private final SodPolicy sodPolicy;
    private final PlatformBankLookup platformBanks;
    private final ObjectMapper objectMapper;
    private final Map<ChangeType, PendingChangeApplier> appliers;

    /**
     * REQUIRES_NEW runner for the FAILED bookkeeping: the approval's own
     * transaction rolls back when an applier refuses, but the fact that an
     * approved apply failed must survive it.
     */
    private final TransactionTemplate independentTx;

    public PendingChangeUseCase(CurrentUserProvider currentUser, AppUserRepository users,
                                PendingChangeRepository changes, AuditRecorder audit,
                                PermissionChecker perm, SodPolicy sodPolicy,
                                PlatformBankLookup platformBanks, ObjectMapper objectMapper,
                                List<PendingChangeApplier> applierBeans,
                                PlatformTransactionManager transactionManager) {
        this.currentUser = currentUser;
        this.users = users;
        this.changes = changes;
        this.audit = audit;
        this.perm = perm;
        this.sodPolicy = sodPolicy;
        this.platformBanks = platformBanks;
        this.objectMapper = objectMapper;
        EnumMap<ChangeType, PendingChangeApplier> byType = new EnumMap<>(ChangeType.class);
        for (PendingChangeApplier applier : applierBeans) {
            byType.put(applier.type(), applier);
        }
        this.appliers = byType;
        this.independentTx = new TransactionTemplate(transactionManager);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@link #submit(ChangeType, JsonNode, String)} with the type still a string (generic endpoint). */
    @Transactional
    public PendingChangeDto submit(String changeType, JsonNode payload, String makerComment) {
        return submit(parseChangeType(changeType), payload, makerComment);
    }

    /**
     * Maker side: validates the payload through the type's applier (the
     * maker gets the direct endpoint's 400/404/409 immediately) and
     * persists the change as REQUESTED. The maker must hold the SAME
     * permission the direct operation required.
     */
    @Transactional
    public PendingChangeDto submit(ChangeType type, JsonNode payload, String makerComment) {
        requireMakerPermission(type);
        PendingChangeApplier.Prepared prepared = applierFor(type).prepare(payload);

        PendingChange change = new PendingChange();
        change.setBankId(currentUser.requireBankId());
        change.setChangeType(type);
        change.setTargetEntityType(type.targetEntityType());
        change.setTargetEntityId(prepared.targetEntityId());
        change.setPayloadJson(prepared.payload().toString());
        change.setStatus(PendingChangeStatus.REQUESTED);
        change.setMakerUserId(currentUser.requireUserId());
        change.setMakerComment(clean(makerComment));
        change.setRequestedAt(LocalDateTime.now());
        PendingChange saved = changes.save(change);

        // Deliberately no payload in the audit values — it may carry PII
        // (emails, names); the PendingChange row itself is the record.
        audit.record(AuditEventSpec.builder("CHANGE_REQUESTED", AuditOutcome.SUCCESS)
                .entity("PendingChange", String.valueOf(saved.getPendingChangeId()))
                .newValues(summary(saved))
                .build());
        return toDto(saved);
    }

    /**
     * Checker side, the B7 gate: a DIFFERENT user approves, the applier
     * executes in this same transaction, the row becomes APPLIED. The maker
     * approving their own change is a hard 409, audited
     * SELF_APPROVAL_BLOCKED (DENIED).
     */
    @Transactional
    public PendingChangeDto approve(int pendingChangeId, String checkerComment) {
        PendingChange change = requireOwnBankChange(pendingChangeId);
        requireRequested(change);
        requireCheckerAudience(change);
        int checkerId = currentUser.requireUserId();
        try {
            // The shared SoD mechanism (SEC-024 runtime layer): the same
            // policy the ws11 document release calls.
            sodPolicy.verifyDistinct(checkerId, change.getMakerUserId(),
                    SodPolicy.Operation.CHANGE_APPROVAL);
        } catch (SodPolicy.SodViolationException blocked) {
            // DENIED writes in its own transaction (JpaAuditRecorder), so
            // this evidence row survives the 409 below.
            audit.record(AuditEventSpec.builder(blocked.operation().auditAction(),
                            AuditOutcome.DENIED)
                    .entity("PendingChange", String.valueOf(change.getPendingChangeId()))
                    .newValues(summary(change))
                    .build());
            throw blocked;
        }
        // Defense in depth: PendingChange rows are bank-filtered (and
        // RLS-covered on PostgreSQL), but AppUsers is not under RLS — assert
        // the maker/checker bank equality explicitly.
        AppUser maker = users.getById(change.getMakerUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "The maker of this change no longer exists."));
        if (maker.getBankId() != currentUser.requireBankId()) {
            throw new IllegalStateException(
                    "The maker and the checker must belong to the same bank.");
        }

        try {
            // Same transaction as the approval bookkeeping below: the change
            // applies and the row flips to APPLIED atomically. The applier
            // re-runs every invariant (last-admin guard included) — state
            // may have moved between submit and approve.
            applierFor(change.getChangeType()).apply(change);
        } catch (RuntimeException applyRefused) {
            markFailedIndependently(change.getPendingChangeId(), checkerId,
                    clean(checkerComment));
            // FAILED writes in its own transaction too; the refusal (e.g.
            // the 409 of the last-admin guard) still reaches the checker.
            audit.record(AuditEventSpec.builder("CHANGE_APPLY_FAILED", AuditOutcome.FAILED)
                    .entity("PendingChange", String.valueOf(change.getPendingChangeId()))
                    .newValues(Map.of("changeType", change.getChangeType().name(),
                            "error", String.valueOf(applyRefused.getMessage())))
                    .build());
            throw applyRefused;
        }

        LocalDateTime now = LocalDateTime.now();
        change.setStatus(PendingChangeStatus.APPLIED);
        change.setCheckerUserId(checkerId);
        change.setCheckerComment(clean(checkerComment));
        change.setDecidedAt(now);
        change.setAppliedAt(now);
        PendingChange saved = changes.save(change);
        audit.record(AuditEventSpec.builder("CHANGE_APPROVED", AuditOutcome.SUCCESS)
                .entity("PendingChange", String.valueOf(saved.getPendingChangeId()))
                .newValues(summary(saved))
                .build());
        return toDto(saved);
    }

    /** Checker side: refuses the change; nothing is applied. */
    @Transactional
    public PendingChangeDto reject(int pendingChangeId, String checkerComment) {
        PendingChange change = requireOwnBankChange(pendingChangeId);
        requireRequested(change);
        requireCheckerAudience(change);
        change.setStatus(PendingChangeStatus.REJECTED);
        change.setCheckerUserId(currentUser.requireUserId());
        change.setCheckerComment(clean(checkerComment));
        change.setDecidedAt(LocalDateTime.now());
        PendingChange saved = changes.save(change);
        audit.record(AuditEventSpec.builder("CHANGE_REJECTED", AuditOutcome.SUCCESS)
                .entity("PendingChange", String.valueOf(saved.getPendingChangeId()))
                .newValues(summary(saved))
                .build());
        return toDto(saved);
    }

    /** Maker side: withdraws an undecided request. Only the maker can. */
    @Transactional
    public PendingChangeDto cancel(int pendingChangeId) {
        PendingChange change = requireOwnBankChange(pendingChangeId);
        requireRequested(change);
        if (currentUser.requireUserId() != change.getMakerUserId()) {
            throw new IllegalStateException("Only the maker can cancel a pending change.");
        }
        change.setStatus(PendingChangeStatus.CANCELLED);
        change.setDecidedAt(LocalDateTime.now());
        PendingChange saved = changes.save(change);
        audit.record(AuditEventSpec.builder("CHANGE_CANCELLED", AuditOutcome.SUCCESS)
                .entity("PendingChange", String.valueOf(saved.getPendingChangeId()))
                .newValues(summary(saved))
                .build());
        return toDto(saved);
    }

    /**
     * The caller's bank's changes, newest first, optionally filtered by
     * status: the rows the caller may decide ({@link ChangeAudience}) plus
     * the caller's own submissions. Listing needs CHANGE_APPROVE or the
     * maker permission of at least one change type.
     */
    @Transactional(readOnly = true)
    public List<PendingChangeDto> list(String status) {
        Deciders deciders = deciders();
        if (!deciders.approver() && !deciders.maker()) {
            throw new AccessDeniedException("Listing pending changes requires an approval "
                    + "or maker permission.");
        }
        int bankId = currentUser.requireBankId();
        int callerId = currentUser.requireUserId();
        List<PendingChange> rows = status == null || status.isBlank()
                ? changes.listByBank(bankId)
                : changes.listByBankAndStatus(bankId, parseStatus(status));
        return rows.stream()
                .filter(row -> row.getMakerUserId() == callerId || deciders.mayDecide(row))
                .map(this::toDto)
                .toList();
    }

    // ------------------------------------------------------------ plumbing

    /**
     * The maker needs the SAME permission the direct operation required
     * (type→permission map on {@link ChangeType}); going through the
     * approval queue must never be a privilege shortcut. PermissionChecker
     * also keeps the forced-password-change gate closed on this path.
     */
    private void requireMakerPermission(ChangeType type) {
        if (!perm.has(type.makerPermission().name())) {
            throw new AccessDeniedException("Requesting a " + type.name() + " change requires "
                    + type.makerPermission().code() + ".");
        }
    }

    /** 403 unless the caller is in the change type's checker audience. */
    private void requireCheckerAudience(PendingChange change) {
        if (!deciders().mayDecide(change)) {
            throw new AccessDeniedException(ChangeAudience.of(change.getChangeType())
                    == ChangeAudience.PLATFORM
                    ? "A " + change.getChangeType().name() + " change is decided by a second "
                    + "platform administrator."
                    : "Deciding a " + change.getChangeType().name() + " change requires "
                    + Permission.CHANGE_APPROVE.code() + ".");
        }
    }

    /** The caller's checker/maker standing, probed once per request. */
    private Deciders deciders() {
        boolean approver = perm.has(Permission.CHANGE_APPROVE.name());
        Set<Permission> makerPermissions = EnumSet.noneOf(Permission.class);
        for (ChangeType type : ChangeType.values()) {
            Permission permission = type.makerPermission();
            if (!makerPermissions.contains(permission) && perm.has(permission.name())) {
                makerPermissions.add(permission);
            }
        }
        return new Deciders(approver, makerPermissions);
    }

    /**
     * What the caller may do in the queue: {@code approver} = holds
     * CHANGE_APPROVE; {@code makerPermissions} = the maker permissions held.
     */
    private final class Deciders {

        private final boolean approver;
        private final Set<Permission> makerPermissions;

        private Deciders(boolean approver, Set<Permission> makerPermissions) {
            this.approver = approver;
            this.makerPermissions = makerPermissions;
        }

        boolean approver() {
            return approver;
        }

        boolean maker() {
            return !makerPermissions.isEmpty();
        }

        /** Whether the caller is in the checker audience of this (own-bank) row. */
        boolean mayDecide(PendingChange change) {
            if (!approver) {
                return false;
            }
            ChangeType type = change.getChangeType();
            return switch (ChangeAudience.of(type)) {
                case BANK -> true;
                case PLATFORM -> makerPermissions.contains(type.makerPermission())
                        && platformBanks.isPlatformBank(change.getBankId());
            };
        }
    }

    private PendingChangeApplier applierFor(ChangeType type) {
        PendingChangeApplier applier = appliers.get(type);
        if (applier == null) {
            throw new IllegalStateException("No applier is registered for " + type + ".");
        }
        return applier;
    }

    /** A change of another bank answers exactly like a missing id. */
    private PendingChange requireOwnBankChange(int pendingChangeId) {
        int bankId = currentUser.requireBankId();
        return changes.getById(pendingChangeId)
                .filter(change -> change.getBankId() == bankId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Pending change " + pendingChangeId + " not found."));
    }

    private static void requireRequested(PendingChange change) {
        if (change.getStatus() != PendingChangeStatus.REQUESTED) {
            throw new IllegalStateException("This change was already decided (status "
                    + change.getStatus() + ").");
        }
    }

    /**
     * Records the FAILED outcome in a transaction of its own: the approval
     * transaction (and the applier's partial work) rolls back around it,
     * the bookkeeping commits regardless.
     */
    private void markFailedIndependently(int pendingChangeId, int checkerId,
                                         String checkerComment) {
        independentTx.executeWithoutResult(tx -> changes.getById(pendingChangeId)
                .ifPresent(fresh -> {
                    fresh.setStatus(PendingChangeStatus.FAILED);
                    fresh.setCheckerUserId(checkerId);
                    fresh.setCheckerComment(checkerComment);
                    fresh.setDecidedAt(LocalDateTime.now());
                    changes.save(fresh);
                }));
    }

    private static ChangeType parseChangeType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A change type is required.");
        }
        try {
            return ChangeType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown change type '" + value + "'.");
        }
    }

    private static PendingChangeStatus parseStatus(String value) {
        try {
            return PendingChangeStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown status '" + value + "'.");
        }
    }

    private static String clean(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    /** The audited view: type and target, never the payload (it may carry PII). */
    private static Map<String, Object> summary(PendingChange change) {
        return Map.of(
                "changeType", change.getChangeType().name(),
                "targetEntityType", String.valueOf(change.getTargetEntityType()),
                "targetEntityId", String.valueOf(change.getTargetEntityId()),
                "makerUserId", change.getMakerUserId());
    }

    private PendingChangeDto toDto(PendingChange change) {
        return new PendingChangeDto(
                change.getPendingChangeId(),
                change.getChangeType().name(),
                change.getTargetEntityType(),
                change.getTargetEntityId(),
                change.getStatus().name(),
                change.getMakerUserId(),
                emailOf(change.getMakerUserId()),
                change.getMakerComment(),
                change.getCheckerUserId(),
                change.getCheckerUserId() == null ? null : emailOf(change.getCheckerUserId()),
                change.getCheckerComment(),
                change.getRequestedAt(),
                change.getDecidedAt(),
                change.getAppliedAt(),
                sanitizedPayload(change.getPayloadJson()));
    }

    private String emailOf(int userId) {
        return users.getById(userId).map(AppUser::getEmail).orElse(null);
    }

    /**
     * Credential material never leaves the backend, not even hashed — at any
     * depth (ws21: BANK_CREATE carries a hash per first user).
     */
    private JsonNode sanitizedPayload(String payloadJson) {
        JsonNode payload = ChangePayloads.parse(objectMapper, payloadJson);
        stripCredentials(payload);
        return payload;
    }

    private static void stripCredentials(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove("passwordHash");
            object.remove("temporaryPassword");
            // ws25: a connection change holds its secret sealed; even the
            // ciphertext stays in the backend.
            object.remove("secretCiphertext");
            object.remove("secret");
            object.elements().forEachRemaining(PendingChangeUseCase::stripCredentials);
        } else if (node != null && node.isArray()) {
            node.elements().forEachRemaining(PendingChangeUseCase::stripCredentials);
        }
    }
}
