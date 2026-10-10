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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA suite (phase 2, Wave 2): the platform/bank boundary on the routes the
 * engineers' suites do not walk. Complements {@link PlatformAccessIntegrationTest}
 * (platform console vs customer-data reads), {@code BankLifecycleIntegrationTest}
 * (every /api/platform/banks route refuses a bank admin) and
 * {@code SettingsApiTest} (both settings layers).
 *
 * <ul>
 *   <li><b>LOOKUP_ADMIN is platform-only</b> (contracts §1): a bank ADMIN gets
 *       403 on every lookup write; the platform admin passes the permission
 *       gate (a missing id answers 404, i.e. the controller ran).</li>
 *   <li><b>A PLATFORM_ADMIN reaches no bank-admin surface</b>: bank settings,
 *       the bank dashboard, template publishing, the bank's integrations.</li>
 *   <li><b>USER_ADMIN of a platform admin stops at the platform bank</b>: the
 *       ws22 user actions on a customer bank's user look like a missing id.</li>
 *   <li><b>Shared surfaces stay open</b>: version and active announcements
 *       answer the platform admin too (SYSTEM_INFO, PROFILE_SELF).</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the platform bank is resolved in its own
 * transaction, so fixtures are committed and removed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformBoundaryQaTest {

    private static final int MISSING_ID = 987_654_321;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;

    private final List<AppUser> created = new ArrayList<>();
    private String platformAdmin;
    private String bankAdmin;
    private AppUser customerUser;

    @BeforeEach
    void seed() {
        Bank platform = PlatformBankFixture.ensurePlatformBank(banks);
        platformAdmin = token(platform.getBankId(), Role.PLATFORM_ADMIN);
        bankAdmin = token(Bank.DEFAULT_BANK_ID, Role.ADMIN);
        customerUser = PlatformBankFixture.user(users, Bank.DEFAULT_BANK_ID, Role.READ_WRITE,
                "qa-boundary");
        created.add(customerUser);
    }

    @AfterEach
    void cleanUp() {
        users.deleteAll(created);
        created.clear();
    }

    private String token(int bankId, Role role) {
        AppUser user = PlatformBankFixture.user(users, bankId, role, "qa-boundary");
        created.add(user);
        return "Bearer " + jwtService.issueToken(user);
    }

    // --------------------------------------------------------- LOOKUP_ADMIN

    @Test
    void lookupWritesAreRefusedToABankAdmin() throws Exception {
        List<String> failures = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : lookupWrites()) {
            int status = status(bankAdmin, request);
            if (status != 403) {
                failures.add(describe(request) + " -> " + status);
            }
        }
        assertThat(failures).as("bank ADMIN must not hold LOOKUP_ADMIN").isEmpty();
    }

    @Test
    void lookupWritesPassThePermissionGateForThePlatformAdmin() throws Exception {
        // Only the non-mutating probes: a missing id proves the controller
        // ran (404) without touching the shared lookup tables.
        assertThat(status(platformAdmin, delete("/TemplateDataTypes/" + MISSING_ID)))
                .isEqualTo(404);
        assertThat(status(platformAdmin, delete("/ProcessStep/" + MISSING_ID))).isEqualTo(404);
        assertThat(status(platformAdmin, json(put("/TemplateDataTypes/Update"),
                "{\"templateDataTypeId\":" + MISSING_ID + ",\"dataType\":\"QA\"}")))
                .isEqualTo(404);
        // Lookups stay readable for both (LOOKUP_READ).
        assertThat(status(platformAdmin, get("/TemplateDataTypes/GetAll"))).isEqualTo(200);
        assertThat(status(bankAdmin, get("/TemplateDataTypes/GetAll"))).isEqualTo(200);
    }

    private static List<MockHttpServletRequestBuilder> lookupWrites() {
        return List.of(
                json(post("/TemplateDataTypes/Create"), "{\"dataType\":\"QA-escalation\"}"),
                json(put("/TemplateDataTypes/Update"),
                        "{\"templateDataTypeId\":1,\"dataType\":\"QA-escalation\"}"),
                delete("/TemplateDataTypes/" + MISSING_ID),
                json(post("/ProcessStep/Create"), "{\"name\":\"QA-escalation\"}"),
                json(put("/ProcessStep/Update"), "{\"processStepId\":1,\"name\":\"QA\"}"),
                delete("/ProcessStep/" + MISSING_ID));
    }

    // ------------------------------------------- platform admin vs bank admin

    @Test
    void aPlatformAdminReachesNoBankAdministrationSurface() throws Exception {
        List<String> failures = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/admin/settings"),
                json(put("/api/admin/settings"),
                        "{\"changes\":[{\"key\":\"extraction.defaultMode\","
                                + "\"value\":\"CODE_ONLY\",\"expectedVersion\":0}]}"),
                get("/api/admin/dashboard"),
                json(put("/Templates/" + MISSING_ID + "/Visibility"),
                        "{\"visibility\":\"BANK\"}"))) {
            int status = status(platformAdmin, request);
            if (status != 403) {
                failures.add(describe(request) + " -> " + status);
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * Wave 3 readiness: the integration admin routes (INTEGRATION_ADMIN /
     * INTEGRATION_RUN, bank-only) land in parallel. Until a route exists it
     * answers 404; from the moment it exists, a platform admin must get 403.
     * Never a 2xx either way.
     */
    @Test
    void aPlatformAdminGetsNoIntegrationAdminRouteOnceTheyExist() throws Exception {
        List<String> failures = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/admin/integrations/connections"),
                get("/api/admin/integrations/flows"),
                get("/api/admin/integrations/api-clients"),
                get("/api/admin/integrations/agents"),
                get("/api/admin/integrations/runs"),
                get("/api/admin/integrations/egress-rules"))) {
            int status = status(platformAdmin, request);
            if (status != 403 && status != 404) {
                failures.add(describe(request) + " -> " + status);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void aPlatformAdminsUserAdminStopsAtThePlatformBank() throws Exception {
        // USER_ADMIN is in the platform set (to manage platform-bank users),
        // but a customer bank's user must look like a missing id.
        int id = customerUser.getAppUserId();
        List<String> failures = new ArrayList<>();
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/admin/users/" + id + "/unlock"),
                json(post("/api/admin/users/" + id + "/password-reset"),
                        "{\"temporaryPassword\":\"Qa-Boundary-Temp-Passphrase-42!\"}"),
                json(post("/api/admin/users/" + id + "/mfa-reset"), "{\"reason\":\"qa\"}"),
                json(patch("/api/admin/users/" + id), "{\"displayName\":\"Hijacked\"}"))) {
            int status = status(platformAdmin, request);
            if (status != 404) {
                failures.add(describe(request) + " -> " + status);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(users.findById(id).orElseThrow().getDisplayName())
                .isNotEqualTo("Hijacked");
    }

    @Test
    void versionAndActiveAnnouncementsAnswerThePlatformAdminToo() throws Exception {
        assertThat(status(platformAdmin, get("/api/system/version"))).isEqualTo(200);
        assertThat(status(platformAdmin, get("/api/system/release-notes"))).isEqualTo(200);
        assertThat(status(platformAdmin, get("/api/announcements/active"))).isEqualTo(200);
        // ... and a bank admin may read, never write, platform announcements.
        assertThat(status(bankAdmin, get("/api/announcements/active"))).isEqualTo(200);
        assertThat(status(bankAdmin, json(post("/api/platform/announcements"),
                "{\"kind\":\"INFO\",\"severity\":\"INFO\",\"title\":\"x\",\"body\":\"y\"}")))
                .isEqualTo(403);
    }

    // ------------------------------------------------------------- helpers

    private int status(String bearer, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer))
                .andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,
                                                      String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String describe(MockHttpServletRequestBuilder request) {
        var built = request.buildRequest(new org.springframework.mock.web.MockServletContext());
        return built.getMethod() + " " + built.getRequestURI();
    }
}
