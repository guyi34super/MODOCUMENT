package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.security.PasswordPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ws21 platform-owner seeding: the PLATFORM bank plus exactly two
 * PLATFORM_ADMIN accounts with forced password change, once; idempotent on
 * re-run; unset variables skip; every malformed or unsafe configuration
 * fails startup without writing anything.
 *
 * <p>{@code @Transactional}: the runner's transaction joins the test's, so
 * everything rolls back. Committed platform banks of other test classes are
 * removed inside that transaction first (and so come back afterwards).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BootstrapPlatformRunnerTest {

    private static final String CODE = "BOOT-PLATFORM";
    /** Both satisfy the policy: long, not breached, no email local-part. */
    private static final String PASSWORD_1 = "Platform-Heron-42xq";
    private static final String PASSWORD_2 = "Platform-Kestrel-93zv";

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
    @PersistenceContext
    private EntityManager entityManager;

    private String emailA;
    private String emailB;

    @BeforeEach
    void noPlatformBankYet() {
        banks.deleteAll(banks.findByKind(BankKind.PLATFORM));
        String tag = UUID.randomUUID().toString().substring(0, 8);
        emailA = "ops-a-" + tag + "@moi.example";
        emailB = "ops-b-" + tag + "@moi.example";
    }

    private BootstrapPlatformRunner runner(String emails, String passwords) {
        return new BootstrapPlatformRunner(users, banks, passwordEncoder, passwordPolicy, audit,
                transactionManager, CODE, emails, passwords, false, false);
    }

    private BootstrapPlatformRunner configured() {
        // Mixed case and spaces on purpose: normalized like every account email.
        return runner(" " + emailA.toUpperCase() + " , " + emailB + " ",
                PASSWORD_1 + "," + PASSWORD_2);
    }

    @Test
    void createsThePlatformBankAndTwoPlatformAdminsWithForcedPasswordChange() {
        configured().run(null);

        Bank platform = banks.findByCode(CODE).orElseThrow();
        assertThat(platform.getKind()).isEqualTo(BankKind.PLATFORM);
        assertThat(platform.getName()).isEqualTo("MO Intelligence Platform");
        List<AppUser> admins = users.findByBankIdOrderByEmailAsc(platform.getBankId());
        assertThat(admins).extracting(AppUser::getEmail).containsExactly(emailA, emailB);
        assertThat(admins).allSatisfy(admin -> {
            assertThat(admin.getRole()).isEqualTo(Role.PLATFORM_ADMIN);
            assertThat(admin.isMustChangePassword()).isTrue();
            assertThat(admin.isDisabled()).isFalse();
        });
        assertThat(passwordEncoder.matches(PASSWORD_1, admins.get(0).getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD_2, admins.get(1).getPasswordHash())).isTrue();

        assertThat(auditCount("PLATFORM_BOOTSTRAPPED", platform.getBankId())).isEqualTo(1);
        assertThat(auditCount("BOOTSTRAP_ADMIN_CREATED", platform.getBankId())).isEqualTo(2);
    }

    /** OBS-02: the dev-only switch drops the forced change (never the TOTP)... */
    @Test
    void theDevOnlySkipSwitchKeepsTheConfiguredPasswords() {
        new BootstrapPlatformRunner(users, banks, passwordEncoder, passwordPolicy, audit,
                transactionManager, CODE, emailA + "," + emailB, PASSWORD_1 + "," + PASSWORD_2,
                true, true).run(null);

        Bank platform = banks.findByCode(CODE).orElseThrow();
        assertThat(users.findByBankIdOrderByEmailAsc(platform.getBankId()))
                .hasSize(2)
                .allSatisfy(admin -> assertThat(admin.isMustChangePassword()).isFalse());
    }

    /** ... and is refused outright on a production-shaped deployment. */
    @Test
    void theSkipSwitchIsRefusedWithoutDevPaths() {
        BootstrapPlatformRunner prod = new BootstrapPlatformRunner(users, banks, passwordEncoder,
                passwordPolicy, audit, transactionManager, CODE, emailA + "," + emailB,
                PASSWORD_1 + "," + PASSWORD_2, false, true);

        assertThatThrownBy(() -> prod.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_BOOTSTRAP_ADMIN_SKIP_PASSWORD_CHANGE");
        assertThat(banks.findByCode(CODE)).isEmpty();
    }

    @Test
    void aSecondRunIsANoOpAndNeverResetsAPassword() {
        configured().run(null);
        Bank platform = banks.findByCode(CODE).orElseThrow();
        AppUser first = users.findByEmailIgnoreCase(emailA).orElseThrow();
        String hash = first.getPasswordHash();

        configured().run(null);
        // Even with different values the variables are ignored once seeded.
        runner("x-" + emailA + ",y-" + emailB, PASSWORD_2 + "," + PASSWORD_1).run(null);

        assertThat(banks.findByKind(BankKind.PLATFORM)).hasSize(1);
        assertThat(users.findByBankIdOrderByEmailAsc(platform.getBankId())).hasSize(2);
        assertThat(users.findByEmailIgnoreCase(emailA).orElseThrow().getPasswordHash())
                .isEqualTo(hash);
        assertThat(auditCount("BOOTSTRAP_ADMIN_CREATED", platform.getBankId())).isEqualTo(2);
    }

    @Test
    void oneEmailIsRefused() {
        assertThatThrownBy(() -> runner(emailA, PASSWORD_1 + "," + PASSWORD_2).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 2 distinct email");
        assertThatThrownBy(() -> runner(emailA + "," + emailA.toUpperCase(),
                PASSWORD_1 + "," + PASSWORD_2).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 2 distinct email");
        assertThat(banks.findByCode(CODE)).isEmpty();
    }

    @Test
    void unsetVariablesSkipAndAHalfConfigurationFails() {
        runner("", "").run(null);
        runner(null, null).run(null);
        assertThat(banks.findByCode(CODE)).isEmpty();

        assertThatThrownBy(() -> runner(emailA + "," + emailB, "").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOTH");
        assertThatThrownBy(() -> runner(emailA + "," + emailB, PASSWORD_1).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 2 passwords");
        assertThat(banks.findByCode(CODE)).isEmpty();
    }

    @Test
    void theSecretFileFormTakesOnePasswordPerLine() {
        runner(emailA + "," + emailB, PASSWORD_1 + "\n" + PASSWORD_2).run(null);

        assertThat(passwordEncoder.matches(PASSWORD_2,
                users.findByEmailIgnoreCase(emailB).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void aWeakPasswordFailsWithoutEchoingIt() {
        assertThatThrownBy(() -> runner(emailA + "," + emailB, PASSWORD_1 + ",Password123")
                .run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("entry 2")
                .hasMessageNotContaining("Password123");
        assertThat(banks.findByCode(CODE)).isEmpty();
    }

    @Test
    void anEmailAlreadyInUseFailsBeforeAnythingIsWritten() {
        AppUser existing = new AppUser();
        existing.setEmail(emailB);
        existing.setPasswordHash("hash");
        existing.setBankId(Bank.DEFAULT_BANK_ID);
        users.save(existing);

        assertThatThrownBy(() -> configured().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(emailB);
        assertThat(banks.findByCode(CODE)).isEmpty();
        assertThat(users.findByEmailIgnoreCase(emailA)).isEmpty();
    }

    @Test
    void aCodeThatNamesACustomerBankOrASecondPlatformBankIsRefused() {
        banks.save(new Bank(CODE, "Already a customer"));
        assertThatThrownBy(() -> configured().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CUSTOMER bank");

        banks.delete(banks.findByCode(CODE).orElseThrow());
        banks.save(new Bank("OTHER-PLATFORM", "Elsewhere", BankKind.PLATFORM));
        assertThatThrownBy(() -> configured().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already the platform bank");
        assertThat(banks.findByCode(CODE)).isEmpty();
    }

    private long auditCount(String action, int bankId) {
        return entityManager.createQuery("select count(e) from AuditEvent e"
                        + " where e.action = :action and e.bankId = :bankId", Long.class)
                .setParameter("action", action)
                .setParameter("bankId", bankId)
                .getSingleResult();
    }
}
