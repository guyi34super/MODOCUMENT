package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TenantContext;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.application.security.RoleAssignmentPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Seeds the platform owner (ws21, docs/platform/platform-owner.md): the
 * PLATFORM bank (code {@code modocument.platform.bank-code}, default
 * {@code MOI-PLATFORM}, name "MO Intelligence Platform") and exactly
 * {@link RoleAssignmentPolicy#MIN_ACTIVE_PLATFORM_ADMINS two}
 * PLATFORM_ADMIN accounts, from
 * <ul>
 *   <li>{@code MODOCUMENT_PLATFORM_ADMIN_EMAILS} — two comma-separated,
 *       distinct emails;</li>
 *   <li>{@code MODOCUMENT_PLATFORM_ADMIN_PASSWORDS} (or, preferably,
 *       {@code MODOCUMENT_PLATFORM_ADMIN_PASSWORDS_FILE} — the
 *       SecretFileEnvironmentPostProcessor indirection) — the two temporary
 *       passwords in the same order, one per line (file) or comma-separated.
 *       Each must pass the password policy; there is no weak-password
 *       override for the platform owner.</li>
 * </ul>
 * Both accounts get MustChangePassword, and AuthUseCase forces TOTP
 * enrolment for PLATFORM_ADMIN at first login regardless of settings.
 *
 * <p>Idempotent one-time seeding: the bank is created only when absent, and
 * the accounts only while the platform bank has no PLATFORM_ADMIN at all —
 * once seeded, the variables are ignored (leaving them set is harmless and
 * never resets a password). Unset variables log and skip (dev/test, or a
 * deployment without a platform owner yet); a half or malformed
 * configuration, a weak password, an email already used elsewhere, or a
 * platform code that names a customer bank fails startup — nothing is
 * written in that case (one transaction).
 *
 * <p>Runs after {@link BootstrapAdminRunner} (which only seeds an EMPTY
 * AppUsers table, so it must see the table before the platform admins
 * exist), and as SYSTEM: the Bank table is RLS-protected and no tenant
 * exists at startup (registered in the {@link TenantContext} call-site
 * registry).
 */
@Component
@Order(BootstrapPlatformRunner.ORDER)
public class BootstrapPlatformRunner implements ApplicationRunner {

    /** Right after BootstrapAdminRunner, before the unordered runners (OIDC bank check). */
    static final int ORDER = BootstrapAdminRunner.ORDER + 10;

    static final String PLATFORM_BANK_NAME = "MO Intelligence Platform";

    private static final Logger log = LoggerFactory.getLogger(BootstrapPlatformRunner.class);

    private final AppUserJpaRepository users;
    private final BankJpaRepository banks;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditRecorder audit;
    private final TransactionTemplate tx;
    private final String platformBankCode;
    private final String adminEmails;
    private final String adminPasswords;

    public BootstrapPlatformRunner(AppUserJpaRepository users,
                                   BankJpaRepository banks,
                                   PasswordEncoder passwordEncoder,
                                   PasswordPolicy passwordPolicy,
                                   AuditRecorder audit,
                                   PlatformTransactionManager transactionManager,
                                   @Value("${modocument.platform.bank-code:MOI-PLATFORM}")
                                   String platformBankCode,
                                   @Value("${MODOCUMENT_PLATFORM_ADMIN_EMAILS:}") String adminEmails,
                                   @Value("${MODOCUMENT_PLATFORM_ADMIN_PASSWORDS:}")
                                   String adminPasswords) {
        this.users = users;
        this.banks = banks;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.platformBankCode = platformBankCode;
        this.adminEmails = adminEmails;
        this.adminPasswords = adminPasswords;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean emailsSet = adminEmails != null && !adminEmails.isBlank();
        boolean passwordsSet = adminPasswords != null && !adminPasswords.isBlank();
        if (!emailsSet && !passwordsSet) {
            log.info("MODOCUMENT_PLATFORM_ADMIN_EMAILS/MODOCUMENT_PLATFORM_ADMIN_PASSWORDS are "
                    + "not set; skipping the platform owner bootstrap.");
            return;
        }
        if (!emailsSet || !passwordsSet) {
            throw new IllegalStateException("Set BOTH MODOCUMENT_PLATFORM_ADMIN_EMAILS and "
                    + "MODOCUMENT_PLATFORM_ADMIN_PASSWORDS (or _FILE) to bootstrap the platform "
                    + "owner, or neither.");
        }
        List<String> emails = parseEmails(adminEmails);
        List<String> passwords = parsePasswords(adminPasswords);
        for (int i = 0; i < emails.size(); i++) {
            try {
                passwordPolicy.validate(passwords.get(i), emails.get(i));
            } catch (IllegalArgumentException weak) {
                // Never echo the password; position + email only.
                throw new IllegalStateException("MODOCUMENT_PLATFORM_ADMIN_PASSWORDS entry "
                        + (i + 1) + " (for " + emails.get(i) + ") violates the password policy ("
                        + weak.getMessage() + ")", weak);
            }
        }
        // runAsSystem OUTSIDE the transaction: the tenant is bound to the
        // connection when the transaction begins (TenantAwareJpaTransactionManager).
        TenantContext.runAsSystem(() ->
                tx.executeWithoutResult(status -> bootstrap(emails, passwords)));
    }

    private void bootstrap(List<String> emails, List<String> passwords) {
        // Every check first, every write after: a refusal leaves nothing.
        Bank platform = banks.findByCode(platformBankCode).orElse(null);
        if (platform == null) {
            List<Bank> otherPlatform = banks.findByKind(BankKind.PLATFORM);
            if (!otherPlatform.isEmpty()) {
                throw new IllegalStateException("Bank '" + otherPlatform.get(0).getCode()
                        + "' is already the platform bank, but modocument.platform.bank-code is '"
                        + platformBankCode + "'. Point the setting at the existing platform bank.");
            }
        } else if (platform.getKind() != BankKind.PLATFORM) {
            throw new IllegalStateException("modocument.platform.bank-code '" + platformBankCode
                    + "' names an existing " + platform.getKind() + " bank; the platform owner "
                    + "needs its own bank. Choose an unused code.");
        } else if (alreadySeeded(platform.getBankId())) {
            return;
        }
        for (String email : emails) {
            if (users.existsByEmailIgnoreCase(email)) {
                throw new IllegalStateException("Cannot bootstrap platform admin '" + email
                        + "': an account with this email already exists. Platform admins need "
                        + "accounts of their own.");
            }
        }

        boolean bankCreated = platform == null;
        if (bankCreated) {
            platform = banks.save(new Bank(platformBankCode, PLATFORM_BANK_NAME, BankKind.PLATFORM));
            audit.record(AuditEventSpec.builder("PLATFORM_BOOTSTRAPPED", AuditOutcome.SUCCESS)
                    .bankId(platform.getBankId())
                    .entity("Bank", String.valueOf(platform.getBankId()))
                    .newValues(Map.of(
                            "bankCode", platform.getCode(),
                            "kind", BankKind.PLATFORM.name(),
                            "platformAdmins", emails.size()))
                    .build());
        }
        int bankId = platform.getBankId();
        for (int i = 0; i < emails.size(); i++) {
            AppUser admin = new AppUser();
            admin.setEmail(emails.get(i));
            admin.setPasswordHash(passwordEncoder.encode(passwords.get(i)));
            admin.setDisplayName("Platform Administrator " + (i + 1));
            admin.setBankId(bankId);
            admin.setRole(Role.PLATFORM_ADMIN);
            // Temporary password: forced change at first login; TOTP
            // enrolment is forced for PLATFORM_ADMIN by AuthUseCase.
            admin.setMustChangePassword(true);
            admin.setSettingsJson("{}");
            admin.setCreatedDate(LocalDateTime.now());
            admin.setUpdatedDate(LocalDateTime.now());
            users.save(admin);
            // Same action as the first bank ADMIN (precedent: feeds the
            // privileged-change alert rule); the role tells them apart.
            audit.record(AuditEventSpec.builder("BOOTSTRAP_ADMIN_CREATED", AuditOutcome.SUCCESS)
                    .bankId(bankId)
                    .entity("AppUser", String.valueOf(admin.getAppUserId()))
                    .newValues(Map.of(
                            "email", admin.getEmail(),
                            "role", Role.PLATFORM_ADMIN.name(),
                            "bankCode", platform.getCode(),
                            "mustChangePassword", true))
                    .build());
        }
        log.info("Platform owner bootstrapped: bank '{}'{}, {} PLATFORM_ADMIN accounts created "
                        + "(password change and TOTP enrolment forced at first login).",
                platformBankCode, bankCreated ? " created" : "", emails.size());
    }

    /**
     * Seeding runs once: any PLATFORM_ADMIN in the platform bank (enabled or
     * not) means it already happened, and the variables are ignored.
     */
    private boolean alreadySeeded(int bankId) {
        List<AppUser> admins = users.findByBankIdOrderByEmailAsc(bankId).stream()
                .filter(user -> user.getRole() == Role.PLATFORM_ADMIN)
                .toList();
        if (admins.isEmpty()) {
            return false;
        }
        long active = admins.stream().filter(user -> !user.isDisabled()).count();
        if (active < RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS) {
            log.warn("The platform bank '{}' has only {} active platform admin(s); at least {} "
                            + "are required for maker/checker. Seeding runs once only - add or "
                            + "re-enable an administrator.", platformBankCode, active,
                    RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS);
        } else {
            log.info("Platform owner already bootstrapped (bank '{}', {} active platform admins); "
                    + "MODOCUMENT_PLATFORM_ADMIN_* ignored.", platformBankCode, active);
        }
        return true;
    }

    /** Exactly two distinct, plausible emails, normalized like every account email. */
    static List<String> parseEmails(String raw) {
        Set<String> emails = new LinkedHashSet<>();
        List<String> parts = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        for (String part : parts) {
            if (!part.contains("@") || part.length() > 256) {
                throw new IllegalStateException("MODOCUMENT_PLATFORM_ADMIN_EMAILS contains an "
                        + "invalid email address.");
            }
            emails.add(part.toLowerCase(Locale.ROOT));
        }
        if (parts.size() != RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS
                || emails.size() != RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS) {
            throw new IllegalStateException("MODOCUMENT_PLATFORM_ADMIN_EMAILS must name exactly "
                    + RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS + " distinct email "
                    + "addresses (comma-separated): every platform change needs a second "
                    + "platform admin to approve it.");
        }
        return List.copyOf(emails);
    }

    /**
     * The temporary passwords in email order: one per line (the secret-file
     * form), otherwise comma-separated. Surrounding whitespace is ignored.
     */
    static List<String> parsePasswords(String raw) {
        String separator = raw.contains("\n") ? "\\R" : ",";
        List<String> passwords = Arrays.stream(raw.split(separator))
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .toList();
        if (passwords.size() != RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS) {
            throw new IllegalStateException("MODOCUMENT_PLATFORM_ADMIN_PASSWORDS must hold exactly "
                    + RoleAssignmentPolicy.MIN_ACTIVE_PLATFORM_ADMINS + " passwords, in the same "
                    + "order as MODOCUMENT_PLATFORM_ADMIN_EMAILS (one per line, or "
                    + "comma-separated).");
        }
        return passwords;
    }
}
