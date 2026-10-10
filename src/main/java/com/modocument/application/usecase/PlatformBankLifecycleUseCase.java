package com.modocument.application.usecase;

import com.modocument.application.dto.BankSummary;
import com.modocument.application.dto.UserDto;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.BankDirectory;
import com.modocument.application.port.BankDirectory.BankRef;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TenantContext;
import com.modocument.application.security.RoleAssignmentPolicy;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The platform owner's WRITES on banks (ws21, wave 2): onboarding, suspension,
 * reactivation and the break-glass admin. Onboarding, suspension and the
 * break-glass admin are maker/checker (BANK_CREATE / BANK_SUSPEND /
 * BANK_ADMIN_ADD): their appliers call the {@code apply*} methods here when a
 * SECOND platform admin approves. Reactivation is direct — the lower-risk
 * direction.
 *
 * <p><b>Tenancy.</b> The caller is a platform admin, so the request (and the
 * approval transaction) is bound to the platform bank, under whose RLS every
 * other bank's rows are invisible and unwritable. Each write here therefore
 * runs in a transaction of its OWN, begun as SYSTEM
 * ({@code runAsSystem} + REQUIRES_NEW — the tenant is bound to the
 * connection when a transaction begins), and stamps every row with the
 * right bank explicitly: the new bank's users and their USER_CREATED audit
 * rows are written under {@code TenantContext.runAs(newBank)} so the audit
 * recorder, the logs (MDC) and every BankId agree on the new bank, and the
 * BANK_* audit rows are written twice — once in the customer bank (its own
 * admins see who touched it, with the platform maker/checker ids) and once
 * in the platform bank. These are registered {@code runAsSystem} call sites
 * (see {@link TenantContext}); they read and write the Bank row, the bank's
 * AppUsers/RefreshToken rows and AuditEvent — never any customer content.
 *
 * <p><b>Atomicity.</b> Each operation is ONE system transaction: a bank is
 * onboarded with all of its first users or not at all — the users are
 * written under {@code runAs(newBank)} INSIDE that transaction, so a failed
 * user insert rolls the Bank row back with it. That transaction commits
 * before the approval bookkeeping does (it cannot join the platform-bound
 * approval transaction). For onboarding, should anything fail after that
 * commit — the directory refresh, the APPLIED row, the CHANGE_APPROVED
 * audit — the approval rolls back and a compensating system transaction
 * deletes the new bank and its first users again, audited
 * {@value #ONBOARDING_REVERTED} in the platform bank, so a failed approval
 * never leaves a committed bank behind (the change ends FAILED, or stays
 * REQUESTED when only the bookkeeping failed, and the code is free again). A process crash between the two commits is
 * the one window this cannot close; the BANK_ONBOARDED row then has no
 * CHANGE_APPROVED partner. Suspension and break-glass leave nothing to
 * compensate (re-approval answers "already suspended" / "already exists").
 */
@Service
public class PlatformBankLifecycleUseCase {

    /** At most this many first users per onboarding request. */
    public static final int MAX_ONBOARDING_USERS = 20;

    /** Audit action of the compensating cleanup after a failed onboarding approval. */
    public static final String ONBOARDING_REVERTED = "BANK_ONBOARDING_REVERTED";

    private static final Logger log = LoggerFactory.getLogger(PlatformBankLifecycleUseCase.class);

    private final BankJpaRepository banks;
    private final AppUserJpaRepository users;
    private final RefreshTokenJpaRepository refreshTokens;
    private final AuthUseCase auth;
    private final AuditRecorder audit;
    private final BankDirectory bankDirectory;
    private final CurrentUserProvider currentUser;
    private final PlatformBankUseCase platformBanks;
    private final String platformBankCode;
    private final TransactionTemplate systemTx;
    private final TransactionTemplate systemReadTx;

    public PlatformBankLifecycleUseCase(BankJpaRepository banks, AppUserJpaRepository users,
                                        RefreshTokenJpaRepository refreshTokens,
                                        AuthUseCase auth, AuditRecorder audit,
                                        BankDirectory bankDirectory,
                                        CurrentUserProvider currentUser,
                                        PlatformBankUseCase platformBanks,
                                        PlatformTransactionManager transactionManager,
                                        @Value("${modocument.platform.bank-code:MOI-PLATFORM}")
                                        String platformBankCode) {
        this.banks = banks;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auth = auth;
        this.audit = audit;
        this.bankDirectory = bankDirectory;
        this.currentUser = currentUser;
        this.platformBanks = platformBanks;
        this.platformBankCode = platformBankCode;
        this.systemTx = new TransactionTemplate(transactionManager);
        this.systemTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.systemReadTx = new TransactionTemplate(transactionManager);
        this.systemReadTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.systemReadTx.setReadOnly(true);
    }

    /** A first user of a bank as the change payload carries it (hash only, never a password). */
    public record NewBankUser(String email, String displayName, Role role, String passwordHash) {
    }

    // ------------------------------------------------------------ submit-time checks

    /**
     * Normalizes and checks a new bank code: 2–40 characters of A–Z, 0–9,
     * '-' and '_' (upper-cased), not the platform bank's code and not taken
     * by any bank (a cross-bank read as SYSTEM: the maker's own tenant sees
     * only the platform bank).
     *
     * @throws IllegalArgumentException a malformed code (400)
     * @throws IllegalStateException    a code already in use (409)
     */
    public String requireAvailableCode(String code) {
        String normalized = normalizeCode(code);
        if (normalized.equalsIgnoreCase(platformBankCode)) {
            throw new IllegalStateException("The code " + normalized
                    + " is reserved for the platform bank.");
        }
        boolean taken = inSystem(systemReadTx, () -> banks.findByCode(normalized).isPresent());
        if (taken) {
            throw new IllegalStateException("A bank with the code " + normalized
                    + " already exists.");
        }
        return normalized;
    }

    /** Upper-cased, trimmed code; refuses anything outside the code alphabet (400). */
    public static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,39}")) {
            throw new IllegalArgumentException("A bank code is 2 to 40 characters: letters, "
                    + "digits, '-' or '_', starting with a letter or digit.");
        }
        return normalized;
    }

    /**
     * The customer bank a suspension or break-glass request targets: an
     * unknown id is a 404, the platform bank a 409 (it is never suspended,
     * and its own admins are managed through its user administration).
     */
    public BankRef requireCustomerBank(int bankId, String platformRefusal) {
        BankRef bank = bankDirectory.find(bankId).orElseThrow(() ->
                new NoSuchElementException("Bank " + bankId + " not found."));
        if (bank.isPlatform()) {
            throw new IllegalStateException(platformRefusal);
        }
        return bank;
    }

    // ------------------------------------------------------------ appliers

    /**
     * Applies an approved BANK_CREATE: creates the CUSTOMER bank, then — in
     * the same system transaction, attributed to the new bank — every first
     * user with MustChangePassword (each audited USER_CREATED in the new
     * bank), and audits BANK_ONBOARDED in the new bank and in the platform
     * bank. Code and emails are re-checked: the world may have moved since
     * the maker submitted.
     *
     * @return the new bank
     */
    public BankRef applyOnboarding(String code, String name, List<NewBankUser> firstUsers,
                                   int platformBankId, int makerUserId) {
        int checkerUserId = currentUser.requireUserId();
        List<Integer> createdUsers = new ArrayList<>();
        Bank bank = inSystem(systemTx, () -> {
            String normalized = normalizeCode(code);
            if (normalized.equalsIgnoreCase(platformBankCode)
                    || banks.findByCode(normalized).isPresent()) {
                throw new IllegalStateException("A bank with the code " + normalized
                        + " already exists.");
            }
            for (NewBankUser user : firstUsers) {
                if (users.existsByEmailIgnoreCase(user.email())) {
                    throw new IllegalStateException("An account with the email " + user.email()
                            + " already exists.");
                }
            }
            Bank created = banks.saveAndFlush(new Bank(normalized, name.trim(), BankKind.CUSTOMER));
            int bankId = created.getBankId();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("bankCode", created.getCode());
            values.put("bankName", created.getName());
            values.put("kind", BankKind.CUSTOMER.name());
            values.put("users", firstUsers.size());
            values.put("admins", firstUsers.stream().filter(u -> u.role() == Role.ADMIN).count());
            values.put("makerUserId", makerUserId);
            values.put("checkerUserId", checkerUserId);
            // Same system transaction: a failing user insert rolls the bank back too.
            TenantContext.runAs(bankId, () -> {
                for (NewBankUser user : firstUsers) {
                    createdUsers.add(createBankUser(bankId, user, "BANK_CREATE"));
                }
                recordInBank("BANK_ONBOARDED", bankId, bankId, null, values);
            });
            recordInBank("BANK_ONBOARDED", platformBankId, bankId, null, values);
            return created;
        });
        revertOnboardingIfApprovalRollsBack(bank, List.copyOf(createdUsers), platformBankId,
                makerUserId, checkerUserId);
        bankDirectory.refresh(bank.getBankId());
        return toRef(bank);
    }

    /**
     * The onboarding committed in its own system transaction; when the
     * enclosing approval transaction then rolls back, delete what it created
     * (first users, then the bank) in a compensating system transaction and
     * audit {@value #ONBOARDING_REVERTED} in the platform bank. Nothing to do
     * when there is no enclosing transaction.
     */
    private void revertOnboardingIfApprovalRollsBack(Bank bank, List<Integer> userIds,
                                                     int platformBankId, int makerUserId,
                                                     int checkerUserId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    revertOnboarding(bank, userIds, platformBankId, makerUserId, checkerUserId);
                }
            }
        });
    }

    /** The compensating cleanup (registered runAsSystem call site). */
    private void revertOnboarding(Bank bank, List<Integer> userIds, int platformBankId, int makerUserId,
                          int checkerUserId) {
        int bankId = bank.getBankId();
        try {
            inSystem(systemTx, () -> {
                for (Integer userId : userIds) {
                    refreshTokens.findByUserId(userId).forEach(refreshTokens::delete);
                }
                users.deleteAllByIdInBatch(userIds);
                banks.deleteById(bankId);
                banks.flush();
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("bankCode", bank.getCode());
                values.put("usersRemoved", userIds.size());
                values.put("reason", "approval rolled back after the bank was created");
                values.put("makerUserId", makerUserId);
                values.put("checkerUserId", checkerUserId);
                recordInBank(ONBOARDING_REVERTED, platformBankId, bankId, null, values);
                return null;
            });
            log.warn("Onboarding of bank {} ({}) reverted: the approval rolled back after the "
                    + "bank was created", bank.getCode(), bankId);
        } catch (RuntimeException e) {
            log.error("Could not revert the onboarding of bank {} ({}) after its approval rolled "
                    + "back; remove the bank and its {} first user(s) by hand",
                    bank.getCode(), bankId, userIds.size(), e);
        } finally {
            bankDirectory.refresh(bankId);
        }
    }

    /**
     * Applies an approved BANK_SUSPEND: Status SUSPENDED + SuspendedAt, every
     * account's TokenVersion bumped (each live access token dies at the
     * RevocationValidator on its next request) and every refresh token
     * revoked; sign-in, MFA verify, refresh and OIDC refuse the bank's users
     * from now on. Audited BANK_SUSPENDED in both banks.
     */
    public void applySuspension(int bankId, String reason, int platformBankId, int makerUserId) {
        int checkerUserId = currentUser.requireUserId();
        inSystem(systemTx, () -> {
            Bank bank = banks.findById(bankId).orElseThrow(() ->
                    new NoSuchElementException("Bank " + bankId + " not found."));
            if (bank.getKind() == BankKind.PLATFORM) {
                throw new IllegalStateException("The platform bank cannot be suspended.");
            }
            if (bank.getStatus() == BankStatus.SUSPENDED) {
                throw new IllegalStateException("Bank " + bank.getCode()
                        + " is already suspended.");
            }
            Instant now = Instant.now();
            bank.setStatus(BankStatus.SUSPENDED);
            bank.setSuspendedAt(now);
            banks.saveAndFlush(bank);
            int accounts = users.bumpTokenVersionForBank(bankId, LocalDateTime.now());
            int refreshRevoked = refreshTokens.revokeAllForBank(bankId, now);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("bankCode", bank.getCode());
            values.put("status", BankStatus.SUSPENDED.name());
            values.put("reason", reason);
            values.put("accountsSignedOut", accounts);
            values.put("refreshTokensRevoked", refreshRevoked);
            values.put("makerUserId", makerUserId);
            values.put("checkerUserId", checkerUserId);
            Map<String, Object> before = Map.of("status", BankStatus.ACTIVE.name());
            recordInBank("BANK_SUSPENDED", bankId, bankId, before, values);
            recordInBank("BANK_SUSPENDED", platformBankId, bankId, before, values);
            return null;
        });
        bankDirectory.refresh(bankId);
    }

    /**
     * Applies an approved BANK_ADMIN_ADD (break-glass): a new ADMIN with
     * MustChangePassword in an existing customer bank, audited USER_CREATED
     * in that bank and BANK_ADMIN_ADDED in both banks with the platform
     * maker and checker ids and the stated reason.
     *
     * @return the new user's id
     */
    public int applyAdminAdd(int bankId, NewBankUser admin, String reason, int platformBankId,
                             int makerUserId) {
        int checkerUserId = currentUser.requireUserId();
        return inSystem(systemTx, () -> {
            Bank bank = banks.findById(bankId).orElseThrow(() ->
                    new NoSuchElementException("Bank " + bankId + " not found."));
            if (bank.getKind() == BankKind.PLATFORM) {
                throw new IllegalStateException("Platform administrators are added through the "
                        + "platform's own user administration, not break-glass.");
            }
            if (users.existsByEmailIgnoreCase(admin.email())) {
                throw new IllegalStateException("An account with the email " + admin.email()
                        + " already exists.");
            }
            int[] created = new int[1];
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("bankCode", bank.getCode());
            values.put("email", admin.email());
            values.put("role", Role.ADMIN.name());
            values.put("reason", reason);
            values.put("makerUserId", makerUserId);
            values.put("checkerUserId", checkerUserId);
            TenantContext.runAs(bankId, () -> {
                created[0] = createBankUser(bankId, admin, "BANK_ADMIN_ADD");
                values.put("userId", created[0]);
                recordInBank("BANK_ADMIN_ADDED", bankId, bankId, null, values);
            });
            recordInBank("BANK_ADMIN_ADDED", platformBankId, bankId, null, values);
            return created[0];
        });
    }

    // ------------------------------------------------------------ direct

    /**
     * Reactivates a suspended bank (direct — it restores access that was
     * already dual-approved). Nobody's tokens come back: the bank's users
     * simply sign in afresh. A bank that is not suspended is answered as-is.
     * Audited BANK_REACTIVATED in both banks.
     */
    public BankSummary reactivate(int bankId) {
        int platformBankId = currentUser.requireBankId();
        inSystem(systemTx, () -> {
            Bank bank = banks.findById(bankId).orElseThrow(() ->
                    new NoSuchElementException("Bank " + bankId + " not found."));
            if (bank.getStatus() != BankStatus.SUSPENDED) {
                return null;
            }
            Instant suspendedAt = bank.getSuspendedAt();
            bank.setStatus(BankStatus.ACTIVE);
            bank.setSuspendedAt(null);
            banks.saveAndFlush(bank);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("status", BankStatus.SUSPENDED.name());
            before.put("suspendedAt", suspendedAt == null ? "" : suspendedAt.toString());
            Map<String, Object> after = Map.of("bankCode", bank.getCode(),
                    "status", BankStatus.ACTIVE.name());
            recordInBank("BANK_REACTIVATED", bankId, bankId, before, after);
            recordInBank("BANK_REACTIVATED", platformBankId, bankId, before, after);
            return null;
        });
        bankDirectory.refresh(bankId);
        return platformBanks.getBank(bankId);
    }

    // ------------------------------------------------------------ plumbing

    /**
     * The one way the platform creates a user in a customer bank: the role
     * must be a bank role, the hash was taken when the maker submitted, the
     * account must change its password at first sign-in. Audited exactly
     * like a bank admin's USER_CREATED (plus which platform change made it).
     */
    private int createBankUser(int bankId, NewBankUser user, String via) {
        RoleAssignmentPolicy.requireAssignable(BankKind.CUSTOMER, user.role());
        UserDto created = auth.createUserWithHash(user.email(), user.passwordHash(),
                user.displayName(), bankId, user.role(), true);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", created.email());
        values.put("displayName", created.displayName());
        values.put("role", created.role());
        values.put("mustChangePassword", true);
        values.put("via", via);
        audit.record(AuditEventSpec.builder("USER_CREATED", AuditOutcome.SUCCESS)
                .bankId(bankId)
                .entity("AppUser", String.valueOf(created.userId()))
                .newValues(values)
                .build());
        return created.userId();
    }

    private void recordInBank(String action, int auditBankId, int bankId,
                              Map<String, Object> before, Map<String, Object> after) {
        audit.record(AuditEventSpec.builder(action, AuditOutcome.SUCCESS)
                .bankId(auditBankId)
                .entity("Bank", String.valueOf(bankId))
                .oldValues(before == null ? null : Map.copyOf(before))
                .newValues(new LinkedHashMap<>(after))
                .build());
    }

    private static BankRef toRef(Bank bank) {
        return new BankRef(bank.getBankId(), bank.getCode(), bank.getName(), bank.getKind(),
                bank.getStatus());
    }

    /**
     * Runs {@code work} in a transaction of its own that BEGINS as SYSTEM
     * (runAsSystem outside, REQUIRES_NEW inside: the tenant is stamped on
     * the connection when the transaction begins).
     */
    private static <T> T inSystem(TransactionTemplate template, Supplier<T> work) {
        try {
            return TenantContext.runAsSystem(() -> template.execute(status -> work.get()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Platform bank operation failed.", e);
        }
    }
}
