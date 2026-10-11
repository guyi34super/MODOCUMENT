package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.PendingChangeStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BANK_CREATE never leaves a half-created bank (wave 4).
 *
 * <ul>
 *   <li>A user insert that fails while the first users are created (under
 *       {@code runAs(newBank)}) rolls the new Bank row back with it: bank,
 *       users and BANK_ONBOARDED rows all live in ONE system transaction.
 *       The change is FAILED and the code stays free.</li>
 *   <li>When the approval itself fails AFTER that system transaction
 *       committed (here: persisting the APPLIED row), the compensating
 *       cleanup deletes the bank and its first users again and audits
 *       BANK_ONBOARDING_REVERTED in the platform bank; the change is still
 *       REQUESTED and a second approval onboards the bank normally.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BankCreateAtomicityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMP_ADMIN = "Atomic-Kestrel-58qz!";
    private static final String TEMP_APPROVER = "Atomic-Plover-73mw!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository userRows;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private AppUserRepository users;
    @MockitoSpyBean
    private PendingChangeRepository changes;

    private final List<Integer> createdUsers = new ArrayList<>();
    private Bank platform;
    private AppUser maker;
    private AppUser checker;
    private String tag;

    @BeforeEach
    void seed() {
        platform = PlatformBankFixture.ensurePlatformBank(banks);
        maker = platformAdmin();
        checker = platformAdmin();
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    @AfterEach
    void cleanUp() {
        for (Integer id : createdUsers) {
            jdbc.update("DELETE FROM PendingChange WHERE MakerUserId = ?", id);
        }
        banks.findByCode("AT" + tag).ifPresent(bank -> {
            userRows.findByBankIdOrderByEmailAsc(bank.getBankId()).forEach(userRows::delete);
            banks.delete(bank);
        });
        createdUsers.forEach(userRows::deleteById);
    }

    @Test
    void aFailingUserInsertRollsTheNewBankBack() throws Exception {
        String code = "AT" + tag;
        String approverEmail = "approver-" + tag.toLowerCase(Locale.ROOT) + "@atomic.example";
        int pendingId = submitOnboarding(code, approverEmail);
        // The bank row and the first user are already written when the
        // second user's insert fails.
        doThrow(new DataIntegrityViolationException("simulated AppUsers insert failure"))
                .when(users).save(argThat(user -> user != null
                        && approverEmail.equals(user.getEmail())));

        approve(pendingId, 409);

        assertThat(banks.findByCode(code)).as("no half-created bank").isEmpty();
        assertThat(userRows.findByEmailIgnoreCase(adminEmail())).isEmpty();
        assertThat(userRows.findByEmailIgnoreCase(approverEmail)).isEmpty();
        assertThat(onboardingAudits(code)).as("BANK_ONBOARDED rolled back too").isZero();
        assertThat(changeStatus(pendingId)).isEqualTo(PendingChangeStatus.FAILED.name());
    }

    @Test
    void anApprovalFailingAfterTheBankCommittedIsCompensated() throws Exception {
        String code = "AT" + tag;
        String approverEmail = "approver-" + tag.toLowerCase(Locale.ROOT) + "@atomic.example";
        int pendingId = submitOnboarding(code, approverEmail);
        doThrow(new DataIntegrityViolationException("simulated APPLIED bookkeeping failure"))
                .when(changes).save(argThat((PendingChange change) -> change != null
                        && change.getPendingChangeId() == pendingId
                        && change.getStatus() == PendingChangeStatus.APPLIED));

        approve(pendingId, 409);

        assertThat(banks.findByCode(code)).as("the committed bank was removed again").isEmpty();
        assertThat(userRows.findByEmailIgnoreCase(adminEmail())).isEmpty();
        assertThat(userRows.findByEmailIgnoreCase(approverEmail)).isEmpty();
        assertThat(changeStatus(pendingId)).isEqualTo(PendingChangeStatus.REQUESTED.name());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                        + "'BANK_ONBOARDING_REVERTED' AND BankId = ? "
                        + "AND CAST(NewValues AS VARCHAR) LIKE ?",
                Integer.class, platform.getBankId(), "%" + code + "%")).isEqualTo(1);

        // Nothing is stuck: the same change can now be approved for real.
        org.mockito.Mockito.reset(changes);
        JsonNode applied = approve(pendingId, 200);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        Bank created = banks.findByCode(code).orElseThrow();
        assertThat(userRows.findByBankIdOrderByEmailAsc(created.getBankId())).hasSize(2);
    }

    // ------------------------------------------------------------ helpers

    private String adminEmail() {
        return "admin-" + tag.toLowerCase(Locale.ROOT) + "@atomic.example";
    }

    private int submitOnboarding(String code, String approverEmail) throws Exception {
        String body = "{\"code\":\"" + code + "\",\"name\":\"Atomic " + tag + "\",\"users\":["
                + "{\"email\":\"" + adminEmail() + "\",\"role\":\"ADMIN\","
                + "\"temporaryPassword\":\"" + TEMP_ADMIN + "\"},"
                + "{\"email\":\"" + approverEmail + "\",\"role\":\"APPROVER\","
                + "\"temporaryPassword\":\"" + TEMP_APPROVER + "\"}]}";
        String response = mvc.perform(post("/api/platform/banks")
                        .header("Authorization", bearer(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("pendingChangeId").asInt();
    }

    private JsonNode approve(int pendingId, int expectedStatus) throws Exception {
        String body = mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(checker)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private String changeStatus(int pendingId) {
        return jdbc.queryForObject("SELECT Status FROM PendingChange WHERE PendingChangeId = ?",
                String.class, pendingId);
    }

    private int onboardingAudits(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                        + "'BANK_ONBOARDED' AND CAST(NewValues AS VARCHAR) LIKE ?",
                Integer.class, "%" + code + "%");
        return count == null ? 0 : count;
    }

    private AppUser platformAdmin() {
        AppUser admin = PlatformBankFixture.user(userRows, platform.getBankId(),
                Role.PLATFORM_ADMIN, "atomic");
        createdUsers.add(admin.getAppUserId());
        return admin;
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }
}
