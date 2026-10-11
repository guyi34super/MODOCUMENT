package com.modocument.application.usecase;

import com.modocument.application.dto.EgressRuleRequest;
import com.modocument.application.dto.EgressRuleView;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.BankEgressRule;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.integration.EgressRuleStatus;
import com.modocument.infrastructure.integration.egress.BankEgressGuard;
import com.modocument.infrastructure.net.EgressTargets;
import com.modocument.infrastructure.persistence.BankEgressRuleJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Bank egress rules (ws25, docs/phase2/contracts.md §3 SE1): the private
 * network destinations a bank's DIRECT connections may reach.
 *
 * <p><b>Bank side</b> ({@code /api/admin/integrations/egress-rules},
 * INTEGRATION_ADMIN) — list, request (PENDING, audited
 * {@value #REQUESTED}) and revoke (direct, the lower-risk direction; audited
 * {@value #REVOKED}). Runs in the caller's own tenant.
 *
 * <p><b>Platform side</b> ({@code /api/platform/egress-rules}) — the
 * cross-bank listing (PLATFORM_READ) and the maker/checker approval
 * (PLATFORM_BANKS, {@link ChangeType#EGRESS_RULE_APPROVE}). The platform
 * admin's request and the approval transaction are bound to the PLATFORM
 * bank, under whose RLS no customer rule is visible, so:
 * <ul>
 *   <li>the listing and the submit-time check read as SYSTEM in a read-only
 *       transaction of their own ({@link #listForPlatform},
 *       {@link #requireApprovable});</li>
 *   <li>the applier ({@link #applyApproval}) finds the rule's bank as
 *       SYSTEM (read-only), then writes the approval and its
 *       {@value #APPROVED} audit row under {@code TenantContext.runAs(rule
 *       bank)} in a REQUIRES_NEW transaction; the platform bank's copy of
 *       the audit row joins the approval transaction.</li>
 * </ul>
 * These are registered call sites (see {@link TenantContext}). They read and
 * write BankEgressRule rows (destinations, not customer content) only.
 */
@Service
public class EgressRuleUseCase {

    /** A bank asked for a destination. */
    public static final String REQUESTED = "EGRESS_RULE_REQUESTED";
    /** Two platform admins approved a destination (contracts §5; alert rule platform-change). */
    public static final String APPROVED = "EGRESS_RULE_APPROVED";
    /** A bank revoked a destination (direct). */
    public static final String REVOKED = "EGRESS_RULE_REVOKED";

    static final String ENTITY = "BankEgressRule";

    private static final Set<EgressRuleStatus> OPEN =
            EnumSet.of(EgressRuleStatus.PENDING, EgressRuleStatus.APPROVED);

    private final BankEgressRuleJpaRepository rules;
    private final BankEgressGuard guard;
    private final BankDirectory banks;
    private final CurrentUserProvider currentUser;
    private final PendingChangeRepository changes;
    private final AuditRecorder audit;
    private final TransactionTemplate systemReadTx;
    private final TransactionTemplate bankWriteTx;
    private final Clock clock = Clock.systemUTC();

    public EgressRuleUseCase(BankEgressRuleJpaRepository rules, BankEgressGuard guard,
                             BankDirectory banks, CurrentUserProvider currentUser,
                             PendingChangeRepository changes, AuditRecorder audit,
                             PlatformTransactionManager transactionManager) {
        this.rules = rules;
        this.guard = guard;
        this.banks = banks;
        this.currentUser = currentUser;
        this.changes = changes;
        this.audit = audit;
        this.systemReadTx = new TransactionTemplate(transactionManager);
        this.systemReadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.systemReadTx.setReadOnly(true);
        this.bankWriteTx = new TransactionTemplate(transactionManager);
        this.bankWriteTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ bank

    /** The caller's bank's rules, newest first (every status). */
    @Transactional(readOnly = true)
    public List<EgressRuleView> listForBank() {
        int bankId = currentUser.requireBankId();
        BankDirectory.BankRef bank = bankRef(bankId);
        return rules.findByBankIdOrderByCreatedAtDescBankEgressRuleIdDesc(bankId).stream()
                .map(rule -> toView(rule, bank, null))
                .toList();
    }

    /**
     * Records a PENDING request for a destination of the caller's bank.
     *
     * @throws IllegalArgumentException neither or both of host/cidr, a
     *     malformed host or CIDR, a metadata address (400)
     * @throws IllegalStateException    the same destination is already
     *     pending or approved (409)
     */
    @Transactional
    public EgressRuleView request(EgressRuleRequest request) {
        int bankId = currentUser.requireBankId();
        boolean hasHost = request.host() != null && !request.host().isBlank();
        boolean hasCidr = request.cidr() != null && !request.cidr().isBlank();
        if (hasHost == hasCidr) {
            throw new IllegalArgumentException("Give exactly one of host or cidr.");
        }
        if (request.port() == null || request.port() < 1 || request.port() > 65535) {
            throw new IllegalArgumentException("The port must be between 1 and 65535.");
        }
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty() || reason.length() > BankEgressRule.REASON_MAX) {
            throw new IllegalArgumentException("A reason of at most "
                    + BankEgressRule.REASON_MAX + " characters is required.");
        }
        String host = null;
        String cidr = null;
        if (hasHost) {
            host = EgressTargets.normalizeHost(request.host());
            if (EgressTargets.isIpLiteral(host) && refusedAlways(EgressTargets.literal(host))) {
                throw new IllegalArgumentException("Cloud metadata and wildcard addresses can "
                        + "never be egress destinations.");
            }
        } else {
            EgressTargets.Cidr block = EgressTargets.Cidr.parse(request.cidr());
            cidr = block.canonical();
        }
        int port = request.port();
        String finalHost = host;
        String finalCidr = cidr;
        boolean duplicate = rules.findByBankIdAndStatusIn(bankId, OPEN).stream()
                .anyMatch(rule -> rule.getPort() == port
                        && Objects.equals(rule.getHost(), finalHost)
                        && Objects.equals(rule.getCidr(), finalCidr));
        if (duplicate) {
            throw new IllegalStateException("This destination is already requested or approved.");
        }

        BankEgressRule rule = new BankEgressRule();
        rule.setBankId(bankId);
        rule.setHost(host);
        rule.setCidr(cidr);
        rule.setPort(port);
        rule.setStatus(EgressRuleStatus.PENDING);
        rule.setReason(reason);
        rule.setRequestedByUserId(currentUser.requireUserId());
        rule.setCreatedAt(clock.instant());
        BankEgressRule saved = rules.save(rule);
        audit.success(REQUESTED, ENTITY, String.valueOf(saved.getBankEgressRuleId()), null,
                snapshot(saved));
        return toView(saved, bankRef(bankId), null);
    }

    /**
     * Revokes one of the caller's bank's rules (direct). Already revoked:
     * answered as-is. Another bank's id answers like a missing one (404).
     */
    @Transactional
    public EgressRuleView revoke(int id) {
        int bankId = currentUser.requireBankId();
        BankEgressRule rule = rules.findByBankEgressRuleIdAndBankId(id, bankId)
                .orElseThrow(() -> new NoSuchElementException("Egress rule " + id + " not found."));
        if (rule.getStatus() != EgressRuleStatus.REVOKED) {
            Map<String, Object> before = Map.of("status", rule.getStatus().name());
            rule.setStatus(EgressRuleStatus.REVOKED);
            BankEgressRule saved = rules.save(rule);
            audit.success(REVOKED, ENTITY, String.valueOf(id), before, snapshot(saved));
            invalidateAfterCommit(bankId);
        }
        return toView(rule, bankRef(bankId), null);
    }

    // -------------------------------------------------------------- platform

    /**
     * Every bank's rules in the given status (PENDING, APPROVED or REVOKED),
     * or PENDING + APPROVED when {@code status} is blank — newest first, each
     * with the platform's open approval request, if any. A cross-bank read
     * as SYSTEM (registered call site).
     */
    public List<EgressRuleView> listForPlatform(String status) {
        Set<EgressRuleStatus> statuses = parseStatuses(status);
        List<BankEgressRule> found = inSystemRead(() ->
                rules.findByStatusInOrderByCreatedAtDescBankEgressRuleIdDesc(statuses));
        Map<Integer, Integer> openChanges = openApprovalChanges(currentUser.requireBankId());
        Map<Integer, BankDirectory.BankRef> refs = new HashMap<>();
        return found.stream()
                .map(rule -> toView(rule,
                        refs.computeIfAbsent(rule.getBankId(), this::bankRef),
                        openChanges.get(rule.getBankEgressRuleId())))
                .toList();
    }

    /**
     * EGRESS_RULE_APPROVE submit check, as the platform maker: the rule must
     * exist (404) and be PENDING (409), and no approval of it may already be
     * waiting (409). Read as SYSTEM (registered call site).
     *
     * @return the rule as it stands (for the checker's payload)
     */
    public EgressRuleView requireApprovable(int ruleId) {
        BankEgressRule rule = inSystemRead(() -> rules.findById(ruleId).orElse(null));
        if (rule == null) {
            throw new NoSuchElementException("Egress rule " + ruleId + " not found.");
        }
        if (rule.getStatus() != EgressRuleStatus.PENDING) {
            throw new IllegalStateException("Egress rule " + ruleId + " is "
                    + rule.getStatus().name().toLowerCase(Locale.ROOT) + ", not pending.");
        }
        Integer open = openApprovalChanges(currentUser.requireBankId()).get(ruleId);
        if (open != null) {
            throw new IllegalStateException("Approval of egress rule " + ruleId
                    + " is already requested (change " + open + ").");
        }
        return toView(rule, bankRef(rule.getBankId()), null);
    }

    /**
     * Applies an approved EGRESS_RULE_APPROVE (the checker is the current
     * user): PENDING → APPROVED in the rule's own bank, audited
     * {@value #APPROVED} there and in the platform bank, and the bank's
     * cached rules are dropped so the destination opens at once on this
     * task.
     */
    public void applyApproval(int ruleId, int platformBankId, int makerUserId) {
        int checkerUserId = currentUser.requireUserId();
        Integer ruleBank = inSystemRead(() ->
                rules.findById(ruleId).map(BankEgressRule::getBankId).orElse(null));
        if (ruleBank == null) {
            throw new NoSuchElementException("Egress rule " + ruleId + " not found.");
        }
        Map<String, Object> values = call(() -> TenantContext.runAs(ruleBank, () ->
                bankWriteTx.execute(status -> {
                    BankEgressRule rule = rules.findByBankEgressRuleIdAndBankId(ruleId, ruleBank)
                            .orElseThrow(() -> new NoSuchElementException(
                                    "Egress rule " + ruleId + " not found."));
                    if (rule.getStatus() != EgressRuleStatus.PENDING) {
                        throw new IllegalStateException("Egress rule " + ruleId + " is "
                                + rule.getStatus().name().toLowerCase(Locale.ROOT)
                                + ", not pending.");
                    }
                    rule.setStatus(EgressRuleStatus.APPROVED);
                    rule.setApprovedByUserId(checkerUserId);
                    rule.setApprovedAt(clock.instant());
                    BankEgressRule saved = rules.saveAndFlush(rule);
                    Map<String, Object> after = snapshot(saved);
                    after.put("bankCode", bankCode(ruleBank));
                    after.put("makerUserId", makerUserId);
                    after.put("checkerUserId", checkerUserId);
                    recordApproved(ruleBank, ruleId, after);
                    return after;
                })));
        recordApproved(platformBankId, ruleId, values);
        guard.invalidate(ruleBank);
    }

    // -------------------------------------------------------------- plumbing

    private void recordApproved(int auditBankId, int ruleId, Map<String, Object> after) {
        audit.record(AuditEventSpec.builder(APPROVED, AuditOutcome.SUCCESS)
                .bankId(auditBankId)
                .entity(ENTITY, String.valueOf(ruleId))
                .oldValues(Map.of("status", EgressRuleStatus.PENDING.name()))
                .newValues(new LinkedHashMap<>(after))
                .build());
    }

    /** Rule id → id of the platform bank's REQUESTED EGRESS_RULE_APPROVE change. */
    private Map<Integer, Integer> openApprovalChanges(int platformBankId) {
        Map<Integer, Integer> open = new HashMap<>();
        for (PendingChange change : changes.listByBankAndStatus(platformBankId,
                PendingChangeStatus.REQUESTED)) {
            if (change.getChangeType() != ChangeType.EGRESS_RULE_APPROVE
                    || change.getTargetEntityId() == null) {
                continue;
            }
            try {
                open.putIfAbsent(Integer.parseInt(change.getTargetEntityId()),
                        change.getPendingChangeId());
            } catch (NumberFormatException ignored) {
                // Not a rule id: not ours to interpret.
            }
        }
        return open;
    }

    private static Set<EgressRuleStatus> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return OPEN;
        }
        try {
            return EnumSet.of(EgressRuleStatus.valueOf(status.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + status
                    + "' (PENDING, APPROVED or REVOKED).");
        }
    }

    private static boolean refusedAlways(InetAddress address) {
        return EgressTargets.isMetadata(address) || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }

    private String bankCode(int bankId) {
        return banks.find(bankId).map(BankDirectory.BankRef::code).orElse(null);
    }

    /** The bank's identifying columns (cached directory, read as SYSTEM), or null. */
    private BankDirectory.BankRef bankRef(int bankId) {
        return banks.find(bankId).orElse(null);
    }

    private static Map<String, Object> snapshot(BankEgressRule rule) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (rule.getHost() != null) {
            values.put("host", rule.getHost());
        }
        if (rule.getCidr() != null) {
            values.put("cidr", rule.getCidr());
        }
        values.put("port", rule.getPort());
        values.put("status", rule.getStatus().name());
        values.put("reason", rule.getReason());
        return values;
    }

    static EgressRuleView toView(BankEgressRule rule, BankDirectory.BankRef bank,
                                 Integer pendingChangeId) {
        return new EgressRuleView(rule.getBankEgressRuleId(), rule.getHost(), rule.getCidr(),
                rule.getPort(), rule.getStatus(), rule.getReason(), rule.getRequestedByUserId(),
                rule.getApprovedByUserId(), rule.getCreatedAt(), rule.getBankId(),
                bank == null ? null : bank.code(), bank == null ? null : bank.name(),
                rule.getApprovedAt(), pendingChangeId);
    }

    private void invalidateAfterCommit(int bankId) {
        guard.invalidate(bankId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            guard.invalidate(bankId);
                        }
                    });
        }
    }

    /** A read-only transaction of its own that BEGINS as SYSTEM. */
    private <T> T inSystemRead(java.util.function.Supplier<T> work) {
        return call(() -> TenantContext.runAsSystem(() -> systemReadTx.execute(s -> work.get())));
    }

    private static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Egress rule operation failed.", e);
        }
    }
}
