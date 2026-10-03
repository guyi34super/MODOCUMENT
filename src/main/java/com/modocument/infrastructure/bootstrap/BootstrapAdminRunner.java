package com.modocument.infrastructure.bootstrap;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Seeds the very first administrator. Public self-registration is gone, so a
 * fresh deployment would otherwise have no way to sign in: when AppUsers is
 * empty, an ADMIN is created in the default bank from
 * MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL / MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD.
 *
 * <p>With users already present this is a no-op (the variables are ignored,
 * so leaving them set is harmless). With an empty table and the variables
 * missing the app refuses to start — except under the local/test profiles
 * (modocument.security.permit-dev-paths=true), where it only logs a warning,
 * because test suites boot empty databases all the time.
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final AppUserJpaRepository users;
    private final BankJpaRepository banks;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean permitDevPaths;

    public BootstrapAdminRunner(AppUserJpaRepository users,
                                BankJpaRepository banks,
                                PasswordEncoder passwordEncoder,
                                @Value("${MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL:}") String adminEmail,
                                @Value("${MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD:}") String adminPassword,
                                @Value("${modocument.security.permit-dev-paths:false}") boolean permitDevPaths) {
        this.users = users;
        this.banks = banks;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.permitDevPaths = permitDevPaths;
    }

    @Override
    public void run(ApplicationArguments args) {
        // runAsSystem: startup seeding runs before any tenant exists and
        // must read/create the RLS-protected default Bank row, so it runs
        // with the wildcard (see the call-site registry in TenantContext).
        TenantContext.runAsSystem(this::bootstrapFirstAdmin);
    }

    private void bootstrapFirstAdmin() {
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
        Bank defaultBank = banks.findByCode(Bank.DEFAULT_BANK_CODE)
                // Present in PostgreSQL via V10; the H2 dev/test schema starts
                // empty, so create it there.
                .orElseGet(() -> banks.save(new Bank(Bank.DEFAULT_BANK_CODE, "Default Bank")));
        AppUser admin = new AppUser();
        admin.setEmail(adminEmail.trim().toLowerCase());
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setDisplayName("Administrator");
        admin.setBankId(defaultBank.getBankId());
        admin.setRole(Role.ADMIN);
        admin.setSettingsJson("{}");
        admin.setCreatedDate(LocalDateTime.now());
        admin.setUpdatedDate(LocalDateTime.now());
        users.save(admin);
        log.info("Bootstrap ADMIN '{}' created in bank '{}'.",
                admin.getEmail(), defaultBank.getCode());
    }
}
