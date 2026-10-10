package com.modocument.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws21 role policy end to end: platform admins manage their own platform
 * bank's users through the SAME maker/checker user administration as bank
 * admins — but the platform bank hands out PLATFORM_ADMIN only, and a
 * customer bank never hands it out. Refusals reach the MAKER (400 at
 * submit), never the checker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformUserAdminIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMP_PASSWORD = "Platform-Joiner-58kq";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<AppUser> created = new ArrayList<>();
    private Bank platform;
    private AppUser makerA;
    private AppUser checkerB;
    private AppUser customerAdmin;

    @BeforeEach
    void seed() {
        platform = PlatformBankFixture.ensurePlatformBank(banks);
        makerA = keep(PlatformBankFixture.user(users, platform.getBankId(), Role.PLATFORM_ADMIN,
                "ws21-ua"));
        checkerB = keep(PlatformBankFixture.user(users, platform.getBankId(),
                Role.PLATFORM_ADMIN, "ws21-ua"));
        customerAdmin = keep(PlatformBankFixture.user(users, Bank.DEFAULT_BANK_ID, Role.ADMIN,
                "ws21-ua"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM PendingChange WHERE MakerUserId IN (?, ?)",
                makerA.getAppUserId(), customerAdmin.getAppUserId());
        users.deleteAll(created);
        created.clear();
    }

    @Test
    void aPlatformAdminCreatesAnotherPlatformAdminUnderDualControl() throws Exception {
        String email = "ws21-joiner-" + UUID.randomUUID() + "@moi.example";
        int pendingId = submitCreate(makerA, email, "PLATFORM_ADMIN", 202);

        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(checkerB)))
                .andExpect(status().isOk());

        AppUser joiner = users.findByEmailIgnoreCase(email).orElseThrow();
        keep(joiner);
        assertThat(joiner.getRole()).isEqualTo(Role.PLATFORM_ADMIN);
        assertThat(joiner.getBankId()).isEqualTo(platform.getBankId());
        assertThat(joiner.isMustChangePassword()).isTrue();
    }

    @Test
    void thePlatformBankRefusesBankRolesAtSubmit() throws Exception {
        for (String role : new String[] {"ADMIN", "APPROVER", "READ_WRITE", "READ_ONLY",
                "API_CLIENT"}) {
            submitCreate(makerA, "ws21-no-" + UUID.randomUUID() + "@moi.example", role, 400);
        }
        mvc.perform(put("/api/admin/users/" + checkerB.getAppUserId() + "/role")
                        .header("Authorization", bearer(makerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCustomerBankNeverHandsOutPlatformAdmin() throws Exception {
        submitCreate(customerAdmin, "ws21-escalate-" + UUID.randomUUID() + "@example.com",
                "PLATFORM_ADMIN", 400);
    }

    private int submitCreate(AppUser maker, String email, String role, int expectedStatus)
            throws Exception {
        String body = mvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\","
                                + "\"temporaryPassword\":\"" + TEMP_PASSWORD + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return expectedStatus == 202
                ? MAPPER.readTree(body).get("pendingChangeId").asInt()
                : -1;
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private AppUser keep(AppUser user) {
        created.add(user);
        return user;
    }
}
