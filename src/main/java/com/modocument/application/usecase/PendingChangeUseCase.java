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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

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
 */
@Service
public class PendingChangeUseCase {

    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final PendingChangeRepository changes;
    private final AuditRecorder audit;
    private final PermissionChecker perm;
    private final SodPolicy sodPolicy;
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
                                ObjectMapper objectMapper,
                                List<PendingChangeApplier> applierBeans,
                                PlatformTransactionManager transactionManager) {
        this.currentUser = currentUser;
        this.users = users;
        this.changes = changes;
        this.audit = audit;
        this.perm = perm;
        this.sodPolicy = sodPolicy;
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
     * status. Holders of CHANGE_APPROVE review everything; a maker without
     * it sees only their own submissions.
     */
    @Transactional(readOnly = true)
    public List<PendingChangeDto> list(String status) {
        boolean approver = perm.has(Permission.CHANGE_APPROVE.name());
        boolean maker = perm.has(Permission.USER_ADMIN.name())
                || perm.has(Permission.TEMPLATE_WRITE.name());
        if (!approver && !maker) {
            throw new AccessDeniedException("Listing pending changes requires an approval "
                    + "or maker permission.");
        }
        int bankId = currentUser.requireBankId();
        List<PendingChange> rows = status == null || status.isBlank()
                ? changes.listByBank(bankId)
                : changes.listByBankAndStatus(bankId, parseStatus(status));
        if (!approver) {
            int callerId = currentUser.requireUserId();
            rows = rows.stream().filter(row -> row.getMakerUserId() == callerId).toList();
        }
        return rows.stream().map(this::toDto).toList();
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

    /** Credential material never leaves the backend, not even hashed. */
    private JsonNode sanitizedPayload(String payloadJson) {
        JsonNode payload = ChangePayloads.parse(objectMapper, payloadJson);
        if (payload instanceof ObjectNode object) {
            object.remove("passwordHash");
            object.remove("temporaryPassword");
        }
        return payload;
    }
}
