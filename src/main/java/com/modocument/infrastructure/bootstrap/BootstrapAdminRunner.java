package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TenantContext;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Seeds the first administrators. Public self-registration is gone, so a
 * fresh deployment would otherwise have no way to sign in: when AppUsers is
 * empty, an ADMIN is created in the default bank from
 * MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL / MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD.
 *
 * <p><b>Second administrator (phase 2, QA finding OBS-01).</b> Every user
 * change is maker/checker, so a bank with ONE administrator can never add
 * anyone. MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL /
 * MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD (both optional, both or
 * neither; the password also as {@code _FILE}) seed a second ADMIN in the
 * same transaction, so the first user change already has its checker. At
 * every startup the runner also WARNS loudly when the default bank has
 * users but fewer than two enabled ADMIN/APPROVER accounts; the remedy on a
 * running install is the platform owner's break-glass BANK_ADMIN_ADD.
 *
 * <p><b>Forced password change (OBS-02).</b> Bootstrap passwords live in
 * deployment configuration, so both accounts get MustChangePassword and
 * must choose their own password at first sign-in.
 * MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true turns that off for
 * throwaway dev stacks only: with modocument.security.permit-dev-paths=false
 * the switch refuses startup (BootstrapPlatformRunner honours the same
 * switch and the same refusal).
 *
 * <p>With users already present seeding is a no-op (the variables are
 * ignored, so leaving them set is harmless). With an empty table and the
 * first admin's variables missing the app refuses to start — except under
 * the local/test profiles (modocument.security.permit-dev-paths=true), where
 * it only logs a warning, because test suites boot empty databases all the
 * time. A half-configured or clashing second admin always refuses startup,
 * and nothing is written then.
 *
 * <p>Ordered explicitly, ahead of {@link BootstrapPlatformRunner} (ws21):
 * "AppUsers is empty" must be judged before the platform admins exist.
 */
@Component
@Order(BootstrapAdminRunner.ORDER)
public class BootstrapAdminRunner implements ApplicationRunner {

    /** Early among the startup runners; BootstrapPlatformRunner follows it. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    /** Enabled ADMIN/APPROVER accounts a bank needs for maker/checker to work at all. */
    static final int MIN_APPROVERS = 2;

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final AppUserJpaRepository users;
    private final BankJpaRepository banks;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditRecorder audit;
    private final TransactionTemplate tx;
    private final String adminEmail;
    private final String adminPassword;
    private final String secondAdminEmail;
    private final String secondAdminPassword;
    private final boolean permitDevPaths;
    private final boolean allowWeakPassword;
    private final boolean skipPasswordChange;

    public BootstrapAdminRunner(AppUserJpaRepository users,
                                BankJpaRepository banks,
                                PasswordEncoder passwordEncoder,
                                PasswordPolicy passwordPolicy,
                                AuditRecorder audit,
                                PlatformTransactionManager transactionManager,
                                @Value("${MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL:}") String adminEmail,
                                @Value("${MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD:}") String adminPassword,
                                @Value("${MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL:}")
                                String secondAdminEmail,
                                @Value("${MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD:}")
                                String secondAdminPassword,
                                @Value("${modocument.security.permit-dev-paths:false}") boolean permitDevPaths,
                                @Value("${MODOCUMENT_BOOTSTRAP_ADMIN_ALLOW_WEAK:false}") boolean allowWeakPassword,
                                @Value("${MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE:false}")
                                boolean skipPasswordChange) {
        this.users = users;
        this.banks = banks;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.secondAdminEmail = secondAdminEmail;
        this.secondAdminPassword = secondAdminPassword;
        this.permitDevPaths = permitDevPaths;
        this.allowWeakPassword = allowWeakPassword;
        this.skipPasswordChange = skipPasswordChange;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireSkipPasswordChangeAllowed(skipPasswordChange, permitDevPaths);
        // runAsSystem: startup seeding runs before any tenant exists and
        // must read/create the RLS-protected default Bank row, so it runs
        // with the wildcard (see the call-site registry in TenantContext) —
        // OUTSIDE the transaction, which binds the tenant when it begins.
        TenantContext.runAsSystem(() -> {
            tx.executeWithoutResult(status -> bootstrapFirstAdmins());
            warnWithoutSecondApprover();
        });
    }

    /**
     * The dev-only switch is refused outright on production-shaped
     * deployments (shared by both bootstrap runners).
     */
    static void requireSkipPasswordChangeAllowed(boolean skipPasswordChange,
                                                 boolean permitDevPaths) {
        if (skipPasswordChange && !permitDevPaths) {
            throw new IllegalStateException("MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true "
                    + "is a dev-only switch and is refused when "
                    + "modocument.security.permit-dev-paths is false: bootstrap passwords live in "
                    + "deployment configuration and must be changed at first sign-in.");
        }
    }

    private void bootstrapFirstAdmins() {
        if (users.count() > 0) {
            return;
        }
        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            if (permitDevPaths) {
                log.warn("AppUsers is empty and MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL/"
                        + "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD are not set; skipping bootstrap "
                        + "admin (dev/test profile). Nobody can log in until a user exists.");
                return;
            }
            throw new IllegalStateException(
                    "AppUsers is empty and no bootstrap admin is configured. Set "
                            + "MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL and "
                            + "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD so the first administrator "
                            + "can be created (registration is admin-only).");
        }
        // Every check first, every write after: a refusal leaves nothing.
        List<Seed> seeds = new ArrayList<>();
        seeds.add(new Seed(normalize(adminEmail), adminPassword, "Administrator",
                "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD"));
        Seed second = secondSeed(seeds.get(0).email());
        if (second != null) {
            seeds.add(second);
        }
        for (Seed seed : seeds) {
            validatePassword(seed);
        }

        Bank defaultBank = banks.findByCode(Bank.DEFAULT_BANK_CODE)
                // Present in PostgreSQL via V10; the H2 dev/test schema starts
                // empty, so create it there.
                .orElseGet(() -> banks.save(new Bank(Bank.DEFAULT_BANK_CODE, "Default Bank")));
        boolean mustChange = !skipPasswordChange;
        for (Seed seed : seeds) {
            AppUser admin = new AppUser();
            admin.setEmail(seed.email());
            admin.setPasswordHash(passwordEncoder.encode(seed.password()));
            admin.setDisplayName(seed.displayName());
            admin.setBankId(defaultBank.getBankId());
            admin.setRole(Role.ADMIN);
            // OBS-02: the password came from deployment configuration.
            admin.setMustChangePassword(mustChange);
            admin.setSettingsJson("{}");
            admin.setCreatedDate(LocalDateTime.now());
            admin.setUpdatedDate(LocalDateTime.now());
            users.save(admin);
            // Audited as SYSTEM (no ActorUserId - the thread runs under
            // runAsSystem); the bank is pinned explicitly since the SYSTEM
            // context carries none.
            audit.record(AuditEventSpec.builder("BOOTSTRAP_ADMIN_CREATED", AuditOutcome.SUCCESS)
                    .bankId(defaultBank.getBankId())
                    .entity("AppUser", String.valueOf(admin.getAppUserId()))
                    .newValues(Map.of(
                            "email", admin.getEmail(),
                            "role", Role.ADMIN.name(),
                            "bankCode", defaultBank.getCode(),
                            "mustChangePassword", mustChange))
                    .build());
            log.info("Bootstrap ADMIN '{}' created in bank '{}'{}.", admin.getEmail(),
                    defaultBank.getCode(), mustChange ? " (password change forced at first "
                            + "sign-in)" : " (password change NOT forced: dev-only switch)");
        }
        if (!mustChange) {
            log.warn("SECURITY WARNING: MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE=true - the "
                    + "bootstrap administrators keep their configured passwords. Dev stacks only.");
        }
    }

    /** The optional second admin: both variables or neither, and a distinct email. */
    private Seed secondSeed(String firstEmail) {
        boolean emailSet = secondAdminEmail != null && !secondAdminEmail.isBlank();
        boolean passwordSet = secondAdminPassword != null && !secondAdminPassword.isBlank();
        if (!emailSet && !passwordSet) {
            return null;
        }
        if (!emailSet || !passwordSet) {
            throw new IllegalStateException("Set BOTH MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL and "
                    + "MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD (or _FILE) to seed a second "
                    + "administrator, or neither.");
        }
        String email = normalize(secondAdminEmail);
        if (!email.contains("@")) {
            throw new IllegalStateException(
                    "MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL is not a valid email address.");
        }
        if (email.equals(firstEmail)) {
            throw new IllegalStateException("MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL must differ "
                    + "from MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL: the second administrator is the "
                    + "first one's checker.");
        }
        return new Seed(email, secondAdminPassword, "Second Administrator",
                "MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD");
    }

    /**
     * The first ADMINs are the most valuable accounts there are — the
     * password policy (SEC-021) applies to them like to everyone, unless a
     * dev deployment explicitly opts out.
     */
    private void validatePassword(Seed seed) {
        try {
            passwordPolicy.validate(seed.password(), seed.email());
        } catch (IllegalArgumentException weak) {
            if (!allowWeakPassword) {
                throw new IllegalStateException(
                        seed.variable() + " violates the password policy ("
                                + weak.getMessage() + ") Choose a stronger password, or set "
                                + "MODOCUMENT_BOOTSTRAP_ADMIN_ALLOW_WEAK=true for a throwaway "
                                + "dev environment.", weak);
            }
            log.warn("SECURITY WARNING: {} violates the password policy ({}) but "
                    + "MODOCUMENT_BOOTSTRAP_ADMIN_ALLOW_WEAK=true overrides it. NEVER use this "
                    + "override outside a throwaway dev environment.",
                    seed.variable(), weak.getMessage());
        }
    }

    /**
     * OBS-01: maker/checker needs two different people who can approve. A
     * default bank with users but fewer than {@value #MIN_APPROVERS} enabled
     * ADMIN/APPROVER accounts can never add or change a user — say so loudly
     * at every startup until it is fixed.
     */
    private void warnWithoutSecondApprover() {
        Bank defaultBank = banks.findByCode(Bank.DEFAULT_BANK_CODE).orElse(null);
        if (defaultBank == null) {
            return;
        }
        long enabledUsers = 0;
        long approvers = 0;
        for (Object[] row : users.countByRoleAndDisabled(defaultBank.getBankId())) {
            Role role = (Role) row[0];
            boolean disabled = Boolean.TRUE.equals(row[1]);
            long count = ((Number) row[2]).longValue();
            if (disabled) {
                continue;
            }
            enabledUsers += count;
            if (role == Role.ADMIN || role == Role.APPROVER) {
                approvers += count;
            }
        }
        if (enabledUsers == 0 || approvers >= MIN_APPROVERS) {
            return;
        }
        log.warn("""

                ********************************************************************************
                * MAKER/CHECKER CANNOT WORK IN BANK '{}': only {} enabled ADMIN/APPROVER account(s).
                * Every user change needs a SECOND approver, so no user can be added, changed or
                * disabled in this bank. Fix it:
                *  - fresh install: set MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL and
                *    MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD (or _FILE) before the first start;
                *  - running install: ask the platform owner for a break-glass administrator
                *    (POST /api/platform/banks/{id}/admins, BANK_ADMIN_ADD).
                ********************************************************************************""",
                defaultBank.getCode(), approvers);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** One bootstrap account; {@code variable} names its password in refusals (never the value). */
    private record Seed(String email, String password, String displayName, String variable) {

        @Override
        public String toString() {
            return "Seed[" + email + "]";
        }
    }
}
