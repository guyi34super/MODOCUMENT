package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * First-run seeding: with an empty AppUsers table the configured bootstrap
 * ADMIN — and the optional second ADMIN (OBS-01) — is created in the default
 * bank with a forced password change (OBS-02); with users present it is a
 * no-op; with nothing configured it fails fast in production profiles and
 * only warns under dev/test ones.
 */
@SpringBootTest
@AutoConfigureMockMvc // same context configuration as the API tests (one cached context)
@ActiveProfiles("test")
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class BootstrapAdminRunnerTest {

    /** Satisfies the policy: 19 chars, not breached, no email local-part. */
    private static final String STRONG_PASSWORD = "Bootstrap-Falcon-7q";
    private static final String SECOND_PASSWORD = "Bootstrap-Heron-42zv!";

    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PasswordPolicy passwordPolicy;
    @Autowired
    private AuditRecorder audit;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private BootstrapAdminRunner runner(String email, String password, boolean permitDevPaths) {
        return runner(email, password, permitDevPaths, false);
    }

    private BootstrapAdminRunner runner(String email, String password, boolean permitDevPaths,
                                        boolean allowWeak) {
        return runner(email, password, "", "", permitDevPaths, allowWeak, false);
    }

    private BootstrapAdminRunner runner(String email, String password, String secondEmail,
                                        String secondPassword, boolean permitDevPaths,
                                        boolean allowWeak, boolean skipPasswordChange) {
        return new BootstrapAdminRunner(users, banks, passwordEncoder, passwordPolicy, audit,
                transactionManager, email, password, secondEmail, secondPassword,
                permitDevPaths, allowWeak, skipPasswordChange);
    }

    @Test
    void emptyTableAndConfiguredVarsCreateTheAdmin() {
        users.deleteAll();

        runner("Root@Example.com", STRONG_PASSWORD, false).run(null);

        assertThat(users.count()).isEqualTo(1);
        AppUser admin = users.findAll().get(0);
        assertThat(admin.getEmail()).isEqualTo("root@example.com");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches(STRONG_PASSWORD, admin.getPasswordHash())).isTrue();
        Bank defaultBank = banks.findByCode(Bank.DEFAULT_BANK_CODE).orElseThrow();
        assertThat(admin.getBankId()).isEqualTo(defaultBank.getBankId());
        // OBS-02: a password from deployment configuration is a temporary one.
        assertThat(admin.isMustChangePassword()).isTrue();
    }

    /** OBS-01: the optional second ADMIN is the first one's checker from day one. */
    @Test
    void theOptionalSecondAdminIsSeededTogetherWithTheFirst() {
        users.deleteAll();

        runner("root@example.com", STRONG_PASSWORD, " Second@Example.com ", SECOND_PASSWORD,
                false, false, false).run(null);

        assertThat(users.count()).isEqualTo(2);
        AppUser second = users.findByEmailIgnoreCase("second@example.com").orElseThrow();
        assertThat(second.getEmail()).isEqualTo("second@example.com");
        assertThat(second.getRole()).isEqualTo(Role.ADMIN);
        assertThat(second.isMustChangePassword()).isTrue();
        assertThat(second.getBankId())
                .isEqualTo(users.findByEmailIgnoreCase("root@example.com").orElseThrow().getBankId());
        assertThat(passwordEncoder.matches(SECOND_PASSWORD, second.getPasswordHash())).isTrue();
    }

    @Test
    void aHalfConfiguredSecondAdminFailsFastAndWritesNothing() {
        users.deleteAll();

        assertThatThrownBy(() -> runner("root@example.com", STRONG_PASSWORD,
                "second@example.com", "", false, false, false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD");
        assertThat(users.count()).isZero();
    }

    @Test
    void theSecondAdminMustBeADifferentPerson() {
        users.deleteAll();

        assertThatThrownBy(() -> runner("root@example.com", STRONG_PASSWORD,
                "ROOT@example.com", SECOND_PASSWORD, false, false, false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
        assertThat(users.count()).isZero();
    }

    /** The second admin's password meets the same policy; nothing is half-written. */
    @Test
    void aWeakSecondAdminPasswordFailsFastAndWritesNothing() {
        users.deleteAll();

        assertThatThrownBy(() -> runner("root@example.com", STRONG_PASSWORD,
                "second@example.com", "Password123", false, false, false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_PASSWORD");
        assertThat(users.count()).isZero();
    }

    /** OBS-02 dev escape hatch: keeps the configured password, dev profiles only. */
    @Test
    void theSkipPasswordChangeSwitchWorksOnlyWithDevPaths() {
        users.deleteAll();

        assertThatThrownBy(() -> runner("root@example.com", STRONG_PASSWORD, "", "",
                false, false, true).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE");
        assertThat(users.count()).isZero();

        runner("root@example.com", STRONG_PASSWORD, "", "", true, false, true).run(null);

        assertThat(users.findByEmailIgnoreCase("root@example.com").orElseThrow()
                .isMustChangePassword()).isFalse();
    }

    /**
     * OBS-01 on an existing install: one approver left in the default bank
     * is warned about at every startup (nothing is created — seeding is for
     * an empty table only).
     */
    @Test
    void aDefaultBankWithASingleApproverIsWarnedAboutButLeftAlone(CapturedOutput output) {
        users.deleteAll();
        runner("root@example.com", STRONG_PASSWORD, false).run(null);
        assertThat(output).contains("MAKER/CHECKER CANNOT WORK IN BANK 'DEFAULT'")
                .contains("MODOCUMENT_BOOTSTRAP_SECOND_ADMIN_EMAIL");

        // Second start, the variables still set, now with a second admin
        // configured: still a no-op (seeding is one-time), and no failure.
        runner("root@example.com", STRONG_PASSWORD, "late@example.com", SECOND_PASSWORD,
                false, false, false).run(null);

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmailIgnoreCase("late@example.com")).isEmpty();
    }

    /** Two approvers: no warning. */
    @Test
    void aDefaultBankWithTwoApproversIsNotWarnedAbout(CapturedOutput output) {
        users.deleteAll();

        runner("root@example.com", STRONG_PASSWORD, "second@example.com", SECOND_PASSWORD,
                false, false, false).run(null);

        assertThat(output).doesNotContain("MAKER/CHECKER CANNOT WORK");
    }

    @Test
    void nonEmptyTableIsANoOp() {
        users.deleteAll();
        AppUser existing = new AppUser();
        existing.setEmail("existing@example.com");
        existing.setPasswordHash("hash");
        users.save(existing);

        runner("root@example.com", STRONG_PASSWORD, false).run(null);

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmailIgnoreCase("root@example.com")).isEmpty();
    }

    /** SEC-021 covers the most valuable account of all. */
    @Test
    void weakBootstrapPasswordFailsFast() {
        users.deleteAll();

        assertThatThrownBy(() -> runner("root@example.com", "Password123", false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_BOOTSTRAP_ADMIN_ALLOW_WEAK");
        assertThat(users.count()).isZero();
    }

    /** The documented dev escape hatch: explicit override, loud warning. */
    @Test
    void weakBootstrapPasswordIsAllowedWithTheExplicitOverride() {
        users.deleteAll();

        runner("root@example.com", "Password123", false, true).run(null);

        assertThat(users.count()).isEqualTo(1);
        assertThat(passwordEncoder.matches("Password123",
                users.findAll().get(0).getPasswordHash())).isTrue();
    }

    @Test
    void emptyTableWithoutVarsFailsFastInProduction() {
        users.deleteAll();

        assertThatThrownBy(() -> runner("", "", false).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL");
        assertThat(users.count()).isZero();
    }

    @Test
    void emptyTableWithoutVarsOnlyWarnsUnderDevAndTestProfiles() {
        users.deleteAll();

        runner("", "", true).run(null); // must not throw

        assertThat(users.count()).isZero();
    }
}
