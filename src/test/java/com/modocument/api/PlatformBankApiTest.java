package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.UserMfa;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.UserMfaJpaRepository;
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
 * ws21 {@code GET /api/platform/banks[/{id}]}: the platform console's view
 * of a bank is its identifying columns plus COUNTS — never an email, a file
 * name, an error message or any other row content of the bank.
 *
 * <p>Fixture: one customer bank with an MFA-enrolled ADMIN (signed in), a
 * READ_WRITE without MFA and a disabled READ_ONLY; two ingestion jobs this
 * week (one FAILED) and a FAILED one from ten days ago. Committed (the use
 * case reads in its own transaction) and removed afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformBankApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_FILE = "payroll-secret-ws21.csv";
    private static final String SECRET_ERROR = "row 7 holds IBAN DE00 ws21";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private UserMfaJpaRepository userMfa;
    @Autowired
    private IngestionJobJpaRepository jobs;
    @Autowired
    private JwtService jwtService;

    private final List<AppUser> createdUsers = new ArrayList<>();
    private final List<IngestionJob> createdJobs = new ArrayList<>();
    private Bank customer;
    private AppUser enrolledAdmin;
    private String platformToken;

    @BeforeEach
    void seed() {
        Bank platform = PlatformBankFixture.ensurePlatformBank(banks);
        AppUser platformAdmin = keep(PlatformBankFixture.user(users, platform.getBankId(),
                Role.PLATFORM_ADMIN, "ws21-api"));
        platformToken = "Bearer " + jwtService.issueToken(platformAdmin);

        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        customer = banks.save(new Bank("PB" + tag, "Counted Bank " + tag));
        int bankId = customer.getBankId();

        enrolledAdmin = PlatformBankFixture.user(users, bankId, Role.ADMIN, "ws21-api");
        enrolledAdmin.setLastLoginAt(LocalDateTime.now().minusHours(1));
        keep(users.save(enrolledAdmin));
        UserMfa mfa = new UserMfa();
        mfa.setUserId(enrolledAdmin.getAppUserId());
        mfa.setSecretCiphertext("ciphertext-not-a-secret");
        mfa.setEnabledAt(Instant.now());
        mfa.setCreatedAt(Instant.now());
        userMfa.save(mfa);
        AppUser member = keep(PlatformBankFixture.user(users, bankId, Role.READ_WRITE, "ws21-api"));
        AppUser leaver = PlatformBankFixture.user(users, bankId, Role.READ_ONLY, "ws21-api");
        leaver.setDisabled(true);
        keep(users.save(leaver));

        job(bankId, member, JobStatus.COMPLETED, LocalDateTime.now().minusDays(1));
        job(bankId, member, JobStatus.FAILED, LocalDateTime.now().minusDays(2));
        job(bankId, member, JobStatus.FAILED, LocalDateTime.now().minusDays(10));
    }

    @AfterEach
    void cleanUp() {
        jobs.deleteAll(createdJobs);
        userMfa.deleteById(enrolledAdmin.getAppUserId());
        users.deleteAll(createdUsers);
        banks.delete(customer);
        createdJobs.clear();
        createdUsers.clear();
    }

    @Test
    void theListCarriesCountsOnlyForEveryBank() throws Exception {
        String body = mvc.perform(get("/api/platform/banks").header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode list = MAPPER.readTree(body);
        assertThat(list.isArray()).isTrue();
        JsonNode summary = null;
        for (JsonNode node : list) {
            if (node.get("bankId").asInt() == customer.getBankId()) {
                summary = node;
            }
        }
        assertThat(summary).as("the customer bank is listed").isNotNull();
        assertCounts(summary);
        // The platform bank itself is listed too, as kind PLATFORM.
        assertThat(list.findValuesAsText("kind")).contains("PLATFORM", "CUSTOMER");
        assertNoRowContent(body);
    }

    @Test
    void oneBankCarriesTheSameCountsAndAnUnknownIdIs404() throws Exception {
        String body = mvc.perform(get("/api/platform/banks/" + customer.getBankId())
                        .header("Authorization", platformToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertCounts(MAPPER.readTree(body));
        assertNoRowContent(body);

        mvc.perform(get("/api/platform/banks/987654321").header("Authorization", platformToken))
                .andExpect(status().isNotFound());
    }

    private void assertCounts(JsonNode summary) {
        Set<String> fields = new TreeSet<>();
        for (Iterator<String> names = summary.fieldNames(); names.hasNext(); ) {
            fields.add(names.next());
        }
        // Exactly the contract's BankSummary (docs/phase2/contracts.md §3).
        assertThat(fields).containsExactlyInAnyOrder("bankId", "code", "name", "kind", "status",
                "users", "mfaEnrolledPct", "jobs7d", "failures7d", "aiShare7d", "codeShare7d",
                "lastActivityAt");
        assertThat(summary.get("code").asText()).isEqualTo(customer.getCode());
        assertThat(summary.get("kind").asText()).isEqualTo("CUSTOMER");
        assertThat(summary.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(summary.get("users").asLong()).isEqualTo(2);          // the leaver is out
        assertThat(summary.get("mfaEnrolledPct").asInt()).isEqualTo(50);  // 1 of 2
        assertThat(summary.get("jobs7d").asLong()).isEqualTo(2);         // 10-day-old one is out
        assertThat(summary.get("failures7d").asLong()).isEqualTo(1);
        assertThat(summary.get("aiShare7d").isNull()).isTrue();          // until ws23
        assertThat(summary.get("codeShare7d").isNull()).isTrue();
        assertThat(summary.get("lastActivityAt").isNull()).isFalse();
    }

    /** No email (of anyone), no job file name, no error text — counts only. */
    private static void assertNoRowContent(String body) {
        assertThat(body).doesNotContain("@").doesNotContain(SECRET_FILE)
                .doesNotContain(SECRET_ERROR).doesNotContain("ciphertext");
    }

    private AppUser keep(AppUser user) {
        createdUsers.add(user);
        return user;
    }

    private void job(int bankId, AppUser owner, JobStatus status, LocalDateTime createdDate) {
        IngestionJob job = new IngestionJob();
        job.setBankId(bankId);
        job.setOwnerUserId(owner.getAppUserId());
        job.setTemplateId(1);
        job.setFileName(SECRET_FILE);
        job.setStatus(status);
        job.setErrorMessage(status == JobStatus.FAILED ? SECRET_ERROR : null);
        job.setCreatedDate(createdDate);
        job.setUpdatedDate(createdDate);
        createdJobs.add(jobs.save(job));
    }
}
