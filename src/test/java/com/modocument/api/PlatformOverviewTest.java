package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.BankStatus;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws21 {@code GET /api/platform/overview} (AC-PLT-09): the platform console's
 * landing view lists every CUSTOMER bank as a counts-only BankSummary plus
 * totals — and nothing else. No email, file name, error text or any other
 * row content of a bank; the platform bank itself is not a customer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformOverviewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_FILE = "salaries-ws21-overview.csv";
    private static final String SECRET_ERROR = "row 3 holds account 0042 ws21";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private JwtService jwtService;

    private final List<AppUser> createdUsers = new ArrayList<>();
    private final List<IngestionJob> createdJobs = new ArrayList<>();
    private final List<Bank> createdBanks = new ArrayList<>();
    private Bank platform;
    private Bank active;
    private Bank suspended;
    private String platformToken;

    @BeforeEach
    void seed() {
        platform = PlatformBankFixture.ensurePlatformBank(banks);
        AppUser platformAdmin = keep(PlatformBankFixture.user(users, platform.getBankId(),
                Role.PLATFORM_ADMIN, "ws21-ov"));
        platformToken = "Bearer " + jwtService.issueToken(platformAdmin);

        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        active = bank("OVA" + tag, BankStatus.ACTIVE);
        suspended = bank("OVS" + tag, BankStatus.SUSPENDED);
        AppUser member = keep(PlatformBankFixture.user(users, active.getBankId(),
                Role.READ_WRITE, "ws21-ov"));
        keep(PlatformBankFixture.user(users, active.getBankId(), Role.ADMIN, "ws21-ov"));
        keep(PlatformBankFixture.user(users, suspended.getBankId(), Role.ADMIN, "ws21-ov"));

        IngestionJob job = new IngestionJob();
        job.setBankId(active.getBankId());
        job.setOwnerUserId(member.getAppUserId());
        job.setTemplateId(1);
        job.setFileName(SECRET_FILE);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(SECRET_ERROR);
        job.setCreatedDate(LocalDateTime.now().minusDays(1));
        job.setUpdatedDate(LocalDateTime.now().minusDays(1));
        createdJobs.add(jobs.save(job));
    }

    @AfterEach
    void cleanUp() {
        jobs.deleteAll(createdJobs);
        users.deleteAll(createdUsers);
        banks.deleteAll(createdBanks);
        createdJobs.clear();
        createdUsers.clear();
        createdBanks.clear();
    }

    @Test
    void countsOnlyForEveryCustomerBankWithConsistentTotals() throws Exception {
        String body = mvc.perform(get("/api/platform/overview")
                        .header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode overview = MAPPER.readTree(body);

        assertThat(fieldNames(overview)).containsExactlyInAnyOrder("banks", "totals");
        assertThat(fieldNames(overview.get("totals"))).containsExactlyInAnyOrder(
                "banks", "activeBanks", "suspendedBanks", "users");
        JsonNode list = overview.get("banks");
        JsonNode activeSummary = null;
        JsonNode suspendedSummary = null;
        long suspendedCount = 0;
        long userSum = 0;
        for (JsonNode summary : list) {
            // Exactly the contract's BankSummary — and only customer banks.
            assertThat(fieldNames(summary)).containsExactlyInAnyOrder("bankId", "code", "name",
                    "kind", "status", "users", "mfaEnrolledPct", "jobs7d", "failures7d",
                    "aiShare7d", "codeShare7d", "lastActivityAt");
            assertThat(summary.get("kind").asText()).isEqualTo("CUSTOMER");
            assertThat(summary.get("bankId").asInt()).isNotEqualTo(platform.getBankId());
            if ("SUSPENDED".equals(summary.get("status").asText())) {
                suspendedCount++;
            }
            userSum += summary.get("users").asLong();
            if (summary.get("bankId").asInt() == active.getBankId()) {
                activeSummary = summary;
            }
            if (summary.get("bankId").asInt() == suspended.getBankId()) {
                suspendedSummary = summary;
            }
        }
        assertThat(activeSummary).isNotNull();
        assertThat(activeSummary.get("users").asLong()).isEqualTo(2);
        assertThat(activeSummary.get("jobs7d").asLong()).isEqualTo(1);
        assertThat(activeSummary.get("failures7d").asLong()).isEqualTo(1);
        assertThat(suspendedSummary).isNotNull();
        assertThat(suspendedSummary.get("status").asText()).isEqualTo("SUSPENDED");

        JsonNode totals = overview.get("totals");
        assertThat(totals.get("banks").asLong()).isEqualTo(list.size());
        assertThat(totals.get("suspendedBanks").asLong()).isEqualTo(suspendedCount);
        assertThat(totals.get("activeBanks").asLong()).isEqualTo(list.size() - suspendedCount);
        assertThat(totals.get("users").asLong()).isEqualTo(userSum);

        // Counts only: no email of anyone, no file name, no error text.
        assertThat(body).doesNotContain("@").doesNotContain(SECRET_FILE)
                .doesNotContain(SECRET_ERROR);
    }

    @Test
    void aCustomerBankAdminCannotReadTheOverview() throws Exception {
        AppUser bankAdmin = keep(PlatformBankFixture.user(users, active.getBankId(), Role.ADMIN,
                "ws21-ov"));
        mvc.perform(get("/api/platform/overview")
                        .header("Authorization", "Bearer " + jwtService.issueToken(bankAdmin)))
                .andExpect(status().isForbidden());
    }

    private Bank bank(String code, BankStatus status) {
        Bank bank = new Bank(code, "Overview " + code);
        bank.setStatus(status);
        if (status == BankStatus.SUSPENDED) {
            bank.setSuspendedAt(Instant.now());
        }
        Bank saved = banks.save(bank);
        createdBanks.add(saved);
        return saved;
    }

    private AppUser keep(AppUser user) {
        createdUsers.add(user);
        return user;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}
