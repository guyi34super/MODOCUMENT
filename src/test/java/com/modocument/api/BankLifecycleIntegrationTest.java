package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.BankKind;
import com.modocument.domain.model.BankStatus;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.PlatformBankFixture;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws21 bank lifecycle end to end (AC-PLT-05/06/07, contracts §2/§3): the
 * platform owner onboards a bank, suspends and reactivates it and adds a
 * break-glass administrator — every write but reactivation as maker/checker
 * between TWO platform admins, every refusal reaching the maker at submit.
 *
 * <ul>
 *   <li>Onboarding: 202 BANK_CREATE without any credential in the response;
 *       the maker cannot approve it (409); a second platform admin's
 *       approval creates the CUSTOMER bank and its users (MustChangePassword,
 *       the ADMIN forced into TOTP enrolment) and audits BANK_ONBOARDED in
 *       both banks plus USER_CREATED per user in the new bank.</li>
 *   <li>Fewer than two users, no ADMIN, fewer than two approvers, a
 *       platform role, a weak password or a duplicate are refused (400/409).</li>
 *   <li>Suspension kills live access tokens, refuses login (generic 401,
 *       audited bank_suspended) and refresh (without the token-theft alarm);
 *       reactivation (direct) restores sign-in.</li>
 *   <li>A customer bank ADMIN gets 403 on every /api/platform/** route.</li>
 * </ul>
 *
 * <p>Committed data (the platform writes run in their own transactions);
 * every row is removed afterwards except the append-only audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BankLifecycleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMP_ADMIN = "Onboard-Kestrel-58qz!";
    private static final String TEMP_APPROVER = "Onboard-Plover-73mw!";
    private static final String MEMBER_PASSWORD = "Member-Lantern-91xk!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<Integer> createdUsers = new ArrayList<>();
    private final List<Integer> createdBanks = new ArrayList<>();
    private Bank platform;
    private AppUser makerA;
    private AppUser checkerB;
    private String tag;
    private String clientIp;

    @BeforeEach
    void seed() {
        platform = PlatformBankFixture.ensurePlatformBank(banks);
        makerA = platformAdmin();
        checkerB = platformAdmin();
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        // Distinct per run, so the shared per-IP login throttle never trips.
        clientIp = "10.21." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @AfterEach
    void cleanUp() {
        List<Integer> makers = new ArrayList<>(createdUsers);
        for (Integer maker : makers) {
            jdbc.update("DELETE FROM PendingChange WHERE MakerUserId = ?", maker);
        }
        for (Integer bankId : createdBanks) {
            for (AppUser user : users.findByBankIdOrderByEmailAsc(bankId)) {
                if (!createdUsers.contains(user.getAppUserId())) {
                    createdUsers.add(user.getAppUserId());
                }
            }
        }
        for (Integer id : createdUsers) {
            refreshTokens.findByUserId(id).forEach(refreshTokens::delete);
            users.deleteById(id);
        }
        for (Integer bankId : createdBanks) {
            banks.deleteById(bankId);
        }
        createdUsers.clear();
        createdBanks.clear();
    }

    // ------------------------------------------------------------ onboarding

    @Test
    void onboardingNeedsASecondPlatformAdminAndCreatesTheBankWithItsFirstUsers()
            throws Exception {
        String code = "LC" + tag;
        String adminEmail = "first-admin-" + tag.toLowerCase() + "@newbank.example";
        String approverEmail = "first-approver-" + tag.toLowerCase() + "@newbank.example";

        MvcResult submitted = mvc.perform(post("/api/platform/banks")
                        .header("Authorization", bearer(makerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboarding(code, "Lifecycle Bank " + tag,
                                user(adminEmail, "ADMIN", TEMP_ADMIN),
                                user(approverEmail, "APPROVER", TEMP_APPROVER))))
                .andExpect(status().isAccepted())
                .andReturn();
        String body = submitted.getResponse().getContentAsString();
        JsonNode change = MAPPER.readTree(body);
        assertThat(change.get("changeType").asText()).isEqualTo("BANK_CREATE");
        assertThat(change.get("status").asText()).isEqualTo("REQUESTED");
        // No credential material leaves the backend, not even hashed.
        assertThat(body).doesNotContain(TEMP_ADMIN).doesNotContain(TEMP_APPROVER)
                .doesNotContain("passwordHash").doesNotContain("temporaryPassword");
        assertThat(change.at("/payload/users").size()).isEqualTo(2);
        assertThat(banks.findByCode(code)).as("nothing exists before approval").isEmpty();
        int pendingId = change.get("pendingChangeId").asInt();

        // The listing strips the per-user hashes too.
        String listing = mvc.perform(get("/api/admin/changes")
                        .header("Authorization", bearer(checkerB)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).contains(code).doesNotContain("passwordHash");

        // The maker cannot approve their own onboarding.
        approve(makerA, pendingId, 409);
        assertThat(banks.findByCode(code)).isEmpty();

        JsonNode applied = approve(checkerB, pendingId, 200);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        Bank created = banks.findByCode(code).orElseThrow();
        createdBanks.add(created.getBankId());
        assertThat(applied.get("targetEntityId").asText())
                .isEqualTo(String.valueOf(created.getBankId()));
        assertThat(created.getKind()).isEqualTo(BankKind.CUSTOMER);
        assertThat(created.getStatus()).isEqualTo(BankStatus.ACTIVE);
        assertThat(created.getName()).isEqualTo("Lifecycle Bank " + tag);

        List<AppUser> firstUsers = users.findByBankIdOrderByEmailAsc(created.getBankId());
        assertThat(firstUsers).extracting(AppUser::getEmail)
                .containsExactly(adminEmail, approverEmail);
        assertThat(firstUsers).extracting(AppUser::getRole)
                .containsExactly(Role.ADMIN, Role.APPROVER);
        assertThat(firstUsers).allSatisfy(user ->
                assertThat(user.isMustChangePassword()).isTrue());
        assertThat(passwordEncoder.matches(TEMP_ADMIN, firstUsers.get(0).getPasswordHash()))
                .isTrue();

        // Audited in BOTH banks, plus each first user in the new bank.
        assertThat(auditCount("BANK_ONBOARDED", created.getBankId(), created)).isEqualTo(1);
        assertThat(auditCount("BANK_ONBOARDED", platform.getBankId(), created)).isEqualTo(1);
        assertThat(auditCount("USER_CREATED", created.getBankId())).isEqualTo(2);
        for (int auditBank : new int[] {created.getBankId(), platform.getBankId()}) {
            JsonNode onboarded = auditValues("BANK_ONBOARDED", auditBank, created);
            assertThat(onboarded.get("makerUserId").asInt()).isEqualTo(makerA.getAppUserId());
            assertThat(onboarded.get("checkerUserId").asInt())
                    .isEqualTo(checkerB.getAppUserId());
            assertThat(onboarded.get("users").asInt()).isEqualTo(2);
        }

        // The new ADMIN signs in: the temporary password buys only the
        // forced TOTP enrolment; the APPROVER gets a session that must
        // change its password first.
        JsonNode adminLogin = login(adminEmail, TEMP_ADMIN, 200);
        assertThat(adminLogin.get("mfaRequired").asBoolean()).isTrue();
        assertThat(adminLogin.get("enrollmentRequired").asBoolean()).isTrue();
        JsonNode approverLogin = login(approverEmail, TEMP_APPROVER, 200);
        assertThat(approverLogin.at("/user/mustChangePassword").asBoolean()).isTrue();
    }

    @Test
    void onboardingRefusesWhatCouldNeverRunMakerChecker() throws Exception {
        String admin = "a-" + tag.toLowerCase() + "@x.example";
        String approver = "b-" + tag.toLowerCase() + "@x.example";
        // Fewer than two users.
        submitOnboarding("A" + tag, 400, user(admin, "ADMIN", TEMP_ADMIN));
        // Two users, but only one can approve.
        submitOnboarding("B" + tag, 400, user(admin, "ADMIN", TEMP_ADMIN),
                user(approver, "READ_ONLY", TEMP_APPROVER));
        // Two approvers, no ADMIN.
        submitOnboarding("C" + tag, 400, user(admin, "APPROVER", TEMP_ADMIN),
                user(approver, "APPROVER", TEMP_APPROVER));
        // A platform role in a customer bank.
        submitOnboarding("D" + tag, 400, user(admin, "ADMIN", TEMP_ADMIN),
                user(approver, "PLATFORM_ADMIN", TEMP_APPROVER));
        // The same person twice.
        submitOnboarding("E" + tag, 400, user(admin, "ADMIN", TEMP_ADMIN),
                user(admin.toUpperCase(), "APPROVER", TEMP_APPROVER));
        // A weak temporary password.
        submitOnboarding("F" + tag, 400, user(admin, "ADMIN", TEMP_ADMIN),
                user(approver, "APPROVER", "Password123"));
        // A malformed code.
        submitOnboarding("x", 400, user(admin, "ADMIN", TEMP_ADMIN),
                user(approver, "APPROVER", TEMP_APPROVER));
        // An existing code, and the platform's own code.
        submitOnboarding(Bank.DEFAULT_BANK_CODE, 409, user(admin, "ADMIN", TEMP_ADMIN),
                user(approver, "APPROVER", TEMP_APPROVER));
        submitOnboarding(PlatformBankFixture.CODE, 409, user(admin, "ADMIN", TEMP_ADMIN),
                user(approver, "APPROVER", TEMP_APPROVER));
        // An email that already has an account.
        submitOnboarding("G" + tag, 409, user(makerA.getEmail(), "ADMIN", TEMP_ADMIN),
                user(approver, "APPROVER", TEMP_APPROVER));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE "
                + "ChangeType = 'BANK_CREATE' AND MakerUserId IN (" + idList() + ")",
                Integer.class)).isZero();
    }

    // ------------------------------------------------------------ suspension

    @Test
    void suspensionKillsSessionsAndRefusesSignInUntilReactivated() throws Exception {
        Bank bank = customerBank();
        AppUser member = bankUser(bank, Role.READ_WRITE);
        MvcResult session = loginResult(member.getEmail(), MEMBER_PASSWORD, 200);
        String token = MAPPER.readTree(session.getResponse().getContentAsString())
                .get("token").asText();
        Cookie refresh = session.getResponse().getCookie("modoc_refresh");
        assertThat(refresh).isNotNull();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode change = submit(makerA, "/api/platform/banks/" + bank.getBankId() + "/suspend",
                "{\"reason\":\"Contract ended " + tag + "\"}", 202);
        assertThat(change.get("changeType").asText()).isEqualTo("BANK_SUSPEND");
        assertThat(change.at("/payload/bankCode").asText()).isEqualTo(bank.getCode());
        // Pending: nothing happened yet.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        approve(makerA, change.get("pendingChangeId").asInt(), 409);
        approve(checkerB, change.get("pendingChangeId").asInt(), 200);

        Bank suspended = banks.findById(bank.getBankId()).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo(BankStatus.SUSPENDED);
        assertThat(suspended.getSuspendedAt()).isNotNull();
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getTokenVersion())
                .isEqualTo(member.getTokenVersion() + 1);

        // 1. The live access token dies on its next request.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // 2. The refresh cookie cannot resurrect it — and is NOT mistaken for
        //    a stolen token (no CRITICAL theft alert per suspended user).
        mvc.perform(post("/api/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
        assertThat(auditCount("TOKEN_REFRESH_REUSE_DETECTED", bank.getBankId())).isZero();
        // 3. Sign-in answers like a wrong password; only the audit says why.
        JsonNode refused = login(member.getEmail(), MEMBER_PASSWORD, 401);
        assertThat(refused.get("error").asText()).isEqualTo("Invalid email or password.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = "
                        + "'LOGIN_FAILED' AND BankId = ? "
                        + "AND CAST(NewValues AS VARCHAR) LIKE '%bank_suspended%'",
                Integer.class, bank.getBankId())).isEqualTo(1);
        assertThat(auditCount("BANK_SUSPENDED", bank.getBankId(), bank)).isEqualTo(1);
        assertThat(auditCount("BANK_SUSPENDED", platform.getBankId(), bank)).isEqualTo(1);
        JsonNode suspendedValues = auditValues("BANK_SUSPENDED", platform.getBankId(), bank);
        assertThat(suspendedValues.get("accountsSignedOut").asInt()).isEqualTo(1);
        assertThat(suspendedValues.get("checkerUserId").asInt()).isEqualTo(checkerB.getAppUserId());

        // A second suspension request is refused at submit.
        submit(makerA, "/api/platform/banks/" + bank.getBankId() + "/suspend",
                "{\"reason\":\"again\"}", 409);

        // Reactivation is direct (one platform admin, 200) and restores sign-in.
        JsonNode reactivated = submit(makerA,
                "/api/platform/banks/" + bank.getBankId() + "/reactivate", "", 200);
        assertThat(reactivated.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(banks.findById(bank.getBankId()).orElseThrow().getSuspendedAt()).isNull();
        login(member.getEmail(), MEMBER_PASSWORD, 200);
        assertThat(auditCount("BANK_REACTIVATED", bank.getBankId(), bank)).isEqualTo(1);
        assertThat(auditCount("BANK_REACTIVATED", platform.getBankId(), bank)).isEqualTo(1);
    }

    @Test
    void thePlatformBankCannotBeSuspendedAndAnUnknownBankIs404() throws Exception {
        submit(makerA, "/api/platform/banks/" + platform.getBankId() + "/suspend",
                "{\"reason\":\"no\"}", 409);
        submit(makerA, "/api/platform/banks/987654321/suspend", "{\"reason\":\"no\"}", 404);
        submit(makerA, "/api/platform/banks/987654321/reactivate", "", 404);
        // A reason is mandatory.
        Bank bank = customerBank();
        submit(makerA, "/api/platform/banks/" + bank.getBankId() + "/suspend",
                "{\"reason\":\"\"}", 400);
    }

    // ------------------------------------------------------------ break-glass

    @Test
    void aBreakGlassAdminIsAddedOnlyAfterASecondPlatformAdminApproves() throws Exception {
        Bank bank = customerBank();
        String email = "break-glass-" + tag.toLowerCase() + "@bank.example";
        JsonNode change = submit(makerA, "/api/platform/banks/" + bank.getBankId() + "/admins",
                "{\"email\":\"" + email + "\",\"displayName\":\"Break Glass\","
                        + "\"temporaryPassword\":\"" + TEMP_ADMIN + "\","
                        + "\"reason\":\"Both admins left " + tag + "\"}", 202);
        assertThat(change.get("changeType").asText()).isEqualTo("BANK_ADMIN_ADD");
        assertThat(change.toString()).doesNotContain(TEMP_ADMIN).doesNotContain("passwordHash");
        int pendingId = change.get("pendingChangeId").asInt();

        approve(makerA, pendingId, 409);
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();
        approve(checkerB, pendingId, 200);

        AppUser added = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(added.getBankId()).isEqualTo(bank.getBankId());
        assertThat(added.getRole()).isEqualTo(Role.ADMIN);
        assertThat(added.isMustChangePassword()).isTrue();
        assertThat(auditCount("USER_CREATED", bank.getBankId())).isEqualTo(1);
        for (int auditBank : new int[] {bank.getBankId(), platform.getBankId()}) {
            JsonNode values = auditValues("BANK_ADMIN_ADDED", auditBank, bank);
            assertThat(values.get("makerUserId").asInt()).isEqualTo(makerA.getAppUserId());
            assertThat(values.get("checkerUserId").asInt()).isEqualTo(checkerB.getAppUserId());
            assertThat(values.get("userId").asInt()).isEqualTo(added.getAppUserId());
            assertThat(values.get("reason").asText()).startsWith("Both admins left");
        }
    }

    @Test
    void breakGlassRefusesThePlatformBankDuplicatesAndWeakPasswords() throws Exception {
        Bank bank = customerBank();
        String fresh = "bg-" + tag.toLowerCase() + "@bank.example";
        submit(makerA, "/api/platform/banks/" + platform.getBankId() + "/admins",
                adminAdd(fresh, TEMP_ADMIN), 409);
        submit(makerA, "/api/platform/banks/" + bank.getBankId() + "/admins",
                adminAdd(checkerB.getEmail(), TEMP_ADMIN), 409);
        submit(makerA, "/api/platform/banks/" + bank.getBankId() + "/admins",
                adminAdd(fresh, "Password123"), 400);
        submit(makerA, "/api/platform/banks/987654321/admins", adminAdd(fresh, TEMP_ADMIN), 404);
    }

    // ------------------------------------------------------------ access

    @Test
    void aCustomerBankAdminIsRefusedOnEveryPlatformRoute() throws Exception {
        Bank bank = customerBank();
        AppUser bankAdmin = bankUser(bank, Role.ADMIN);
        String bearer = bearer(bankAdmin);
        mvc.perform(get("/api/platform/overview").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/banks").header("Authorization", bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/banks/" + bank.getBankId())
                        .header("Authorization", bearer))
                .andExpect(status().isForbidden());
        submitAs(bearer, "/api/platform/banks", onboarding("H" + tag, "Escalation",
                user("h1-" + tag.toLowerCase() + "@x.example", "ADMIN", TEMP_ADMIN),
                user("h2-" + tag.toLowerCase() + "@x.example", "APPROVER", TEMP_APPROVER)), 403);
        submitAs(bearer, "/api/platform/banks/" + bank.getBankId() + "/suspend",
                "{\"reason\":\"x\"}", 403);
        submitAs(bearer, "/api/platform/banks/" + bank.getBankId() + "/reactivate", "", 403);
        submitAs(bearer, "/api/platform/banks/" + bank.getBankId() + "/admins",
                adminAdd("i-" + tag.toLowerCase() + "@x.example", TEMP_ADMIN), 403);
    }

    // ------------------------------------------------------------ helpers

    private AppUser platformAdmin() {
        AppUser admin = PlatformBankFixture.user(users, platform.getBankId(), Role.PLATFORM_ADMIN,
                "ws21-lc");
        createdUsers.add(admin.getAppUserId());
        return admin;
    }

    private Bank customerBank() {
        Bank bank = banks.save(new Bank("CB" + tag + createdBanks.size(), "Customer " + tag));
        createdBanks.add(bank.getBankId());
        return bank;
    }

    private AppUser bankUser(Bank bank, Role role) {
        AppUser user = new AppUser();
        user.setEmail("member-" + UUID.randomUUID() + "@bank.example");
        user.setPasswordHash(passwordEncoder.encode(MEMBER_PASSWORD));
        user.setDisplayName("Member");
        user.setBankId(bank.getBankId());
        user.setRole(role);
        user.setSettingsJson("{}");
        user.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(user);
        createdUsers.add(saved.getAppUserId());
        return saved;
    }

    private static ObjectNode user(String email, String role, String temporaryPassword) {
        ObjectNode user = MAPPER.createObjectNode();
        user.put("email", email);
        user.put("displayName", role + " " + email);
        user.put("role", role);
        user.put("temporaryPassword", temporaryPassword);
        return user;
    }

    private static String onboarding(String code, String name, ObjectNode... firstUsers) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("code", code);
        body.put("name", name);
        ArrayNode list = body.putArray("users");
        for (ObjectNode user : firstUsers) {
            list.add(user);
        }
        return body.toString();
    }

    private static String adminAdd(String email, String password) {
        return "{\"email\":\"" + email + "\",\"temporaryPassword\":\"" + password + "\","
                + "\"reason\":\"break-glass test\"}";
    }

    /** Each refusal from a fresh maker: the submit route is rate-limited per user. */
    private void submitOnboarding(String code, int expectedStatus, ObjectNode... firstUsers)
            throws Exception {
        submit(platformAdmin(), "/api/platform/banks", onboarding(code, "Bank " + code,
                firstUsers), expectedStatus);
    }

    private JsonNode submit(AppUser maker, String path, String json, int expectedStatus)
            throws Exception {
        return submitAs(bearer(maker), path, json, expectedStatus);
    }

    private JsonNode submitAs(String bearer, String path, String json, int expectedStatus)
            throws Exception {
        var request = post(path).header("Authorization", bearer);
        if (!json.isEmpty()) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private JsonNode approve(AppUser checker, int pendingId, int expectedStatus)
            throws Exception {
        String body = mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(checker)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private MvcResult loginResult(String email, String password, int expectedStatus)
            throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\""
                                + password + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private JsonNode login(String email, String password, int expectedStatus) throws Exception {
        return MAPPER.readTree(loginResult(email, password, expectedStatus)
                .getResponse().getContentAsString());
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    private int auditCount(String action, int bankId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = ? AND BankId = ?",
                Integer.class, action, bankId);
        return count == null ? 0 : count;
    }

    /** Audit rows about one bank (EntityType Bank) recorded in {@code bankId}. */
    private int auditCount(String action, int bankId, Bank about) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = ? "
                        + "AND BankId = ? AND EntityType = 'Bank' AND EntityId = ?",
                Integer.class, action, bankId, String.valueOf(about.getBankId()));
        return count == null ? 0 : count;
    }

    /** The NewValues of the one such row (H2 may hand JSON back as a JSON string). */
    private JsonNode auditValues(String action, int bankId, Bank about) throws Exception {
        String raw = jdbc.queryForObject("SELECT CAST(NewValues AS VARCHAR) FROM AuditEvent "
                        + "WHERE Action = ? AND BankId = ? AND EntityType = 'Bank' "
                        + "AND EntityId = ?",
                String.class, action, bankId, String.valueOf(about.getBankId()));
        JsonNode node = MAPPER.readTree(raw);
        return node.isTextual() ? MAPPER.readTree(node.asText()) : node;
    }

    private String idList() {
        return String.join(",", createdUsers.stream().map(String::valueOf).toList());
    }
}
