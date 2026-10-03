package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.AuditRecorder;
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

    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditRecorder audit;

    private BootstrapAdminRunner runner(String email, String password, boolean permitDevPaths) {
        return new BootstrapAdminRunner(users, banks, passwordEncoder, audit, email, password,
                permitDevPaths);
    }

    @Test
    void emptyTableAndConfiguredVarsCreateTheAdmin() {
        users.deleteAll();

        runner("Root@Example.com", "Bootstrap1!", false).run(null);

        assertThat(users.count()).isEqualTo(1);
        AppUser admin = users.findAll().get(0);
        assertThat(admin.getEmail()).isEqualTo("root@example.com");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches("Bootstrap1!", admin.getPasswordHash())).isTrue();
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

        runner("root@example.com", "Bootstrap1!", false).run(null);

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmailIgnoreCase("root@example.com")).isEmpty();
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
