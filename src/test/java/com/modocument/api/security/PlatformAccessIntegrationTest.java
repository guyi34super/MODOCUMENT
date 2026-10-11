package com.modocument.api.security;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.PlatformBankFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws21 platform guard over the real HTTP stack (production filter chain +
 * method security): the platform console answers ONLY a PLATFORM_ADMIN
 * whose token names the platform bank, and that platform admin in turn
 * reaches no customer-data route.
 *
 * <p>Not {@code @Transactional}: the permission checker resolves the
 * platform bank in its own transaction, so fixtures are committed (and the
 * users removed afterwards; the platform bank row stays, see
 * {@link PlatformBankFixture}). The "PLATFORM_ADMIN in a customer bank" row
 * exists only on H2 — on PostgreSQL the V210 trigger refuses it
 * (PlatformRoleTriggerPgIT); here it stands in for a forged claim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAccessIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;

    private final List<AppUser> created = new ArrayList<>();
    private String platformAdminToken;
    private String strayPlatformAdminToken;
    private String customerAdminToken;

    @BeforeEach
    void seed() {
        Bank platform = PlatformBankFixture.ensurePlatformBank(banks);
        platformAdminToken = token(platform.getBankId(), Role.PLATFORM_ADMIN);
        strayPlatformAdminToken = token(Bank.DEFAULT_BANK_ID, Role.PLATFORM_ADMIN);
        customerAdminToken = token(Bank.DEFAULT_BANK_ID, Role.ADMIN);
    }

    @AfterEach
    void cleanUp() {
        users.deleteAll(created);
        created.clear();
    }

    private String token(int bankId, Role role) {
        AppUser user = PlatformBankFixture.user(users, bankId, role, "ws21-guard");
        created.add(user);
        return "Bearer " + jwtService.issueToken(user);
    }

    @Test
    void thePlatformAdminOfThePlatformBankReadsTheConsole() throws Exception {
        mvc.perform(get("/api/platform/banks").header("Authorization", platformAdminToken))
                .andExpect(status().isOk());
    }

    @Test
    void aPlatformAdminTokenFromANonPlatformBankIsRefused() throws Exception {
        mvc.perform(get("/api/platform/banks").header("Authorization", strayPlatformAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/banks/" + Bank.DEFAULT_BANK_ID)
                        .header("Authorization", strayPlatformAdminToken))
                .andExpect(status().isForbidden());
        // Its non-platform grants die with it (USER_ADMIN, LOOKUP_READ).
        mvc.perform(get("/api/admin/users").header("Authorization", strayPlatformAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/ProcessStep/GetAll").header("Authorization", strayPlatformAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCustomerBankAdminGets403OnThePlatformConsole() throws Exception {
        mvc.perform(get("/api/platform/banks").header("Authorization", customerAdminToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/banks/" + Bank.DEFAULT_BANK_ID)
                        .header("Authorization", customerAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePlatformAdminReachesNoCustomerDataRoute() throws Exception {
        for (String route : new String[] {"/Templates/GetAll", "/api/ingestion/jobs",
                "/api/extraction/sources", "/api/doccreation/blueprints",
                "/ImportHistory/GetAll"}) {
            mvc.perform(get(route).header("Authorization", platformAdminToken))
                    .andExpect(status().isForbidden());
        }
    }
}
