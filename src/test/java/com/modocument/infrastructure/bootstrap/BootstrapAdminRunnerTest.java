package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * First-run seeding: with an empty AppUsers table the configured bootstrap
 * ADMIN is created in the default bank; with users present it is a no-op;
 * with nothing configured it fails fast in production profiles and only
 * warns under dev/test ones.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BootstrapAdminRunnerTest {

    /** Satisfies the policy: 19 chars, not breached, no email local-part. */
    private static final String STRONG_PASSWORD = "Bootstrap-Falcon-7q";

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

    private BootstrapAdminRunner runner(String email, String password, boolean permitDevPaths) {
        return runner(email, password, permitDevPaths, false);
    }

    private BootstrapAdminRunner runner(String email, String password, boolean permitDevPaths,
                                        boolean allowWeak) {
        return new BootstrapAdminRunner(users, banks, passwordEncoder, passwordPolicy, audit,
                email, password, permitDevPaths, allowWeak);
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
