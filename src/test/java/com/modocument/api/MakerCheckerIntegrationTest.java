package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.RefreshTokenJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE B7 GATE (SEC-023, maker/checker) over real HTTP semantics: a critical
 * change stays pending until a DIFFERENT user approves it; the maker
 * approving their own change is a hard 409 with a SELF_APPROVAL_BLOCKED
 * (DENIED) audit row, and the change stays REQUESTED. Approval by a second
 * user executes the change with the full ws09 mechanics (role change kills
 * the target's outstanding tokens). Reject, cancel, cross-bank blindness,
 * the template pending flow (gate ON via properties — the H2 suite default
 * is off, see application-test.yml) and the apply-time invariant re-run
 * (FAILED bookkeeping) are proven here too;
 * TemplateMakerCheckerConfigOffIntegrationTest proves the documented
 * config-off direct path.
 */
@SpringBootTest(properties = "modocument.security.maker-checker.template-changes=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MakerCheckerIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASSWORD = "Sup3r-secret-ws10!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private RefreshTokenJpaRepository refreshTokens;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    private Bank bankA;
    private Bank bankB;
    private AppUser adminA;
    private AppUser adminB;
    private AppUser approver;
    private AppUser writer;
    private AppUser member;
    private AppUser readOnly;
    private AppUser foreignAdmin;
    private String clientIp;
    private final List<Integer> createdUserIds = new ArrayList<>();
    private final List<Integer> createdTemplateIds = new ArrayList<>();

    @BeforeEach
    void seedBankWithTwoAdminsAnApproverAndAWriter() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bankA = banks.save(new Bank("MCA" + tag.substring(0, 4).toUpperCase(), "MC Bank A"));
        bankB = banks.save(new Bank("MCB" + tag.substring(4).toUpperCase(), "MC Bank B"));
        adminA = storeUser("mc-admin-a-" + tag, bankA.getBankId(), Role.ADMIN);
        adminB = storeUser("mc-admin-b-" + tag, bankA.getBankId(), Role.ADMIN);
        approver = storeUser("mc-approver-" + tag, bankA.getBankId(), Role.APPROVER);
        writer = storeUser("mc-writer-" + tag, bankA.getBankId(), Role.READ_WRITE);
        member = storeUser("mc-member-" + tag, bankA.getBankId(), Role.READ_WRITE);
        readOnly = storeUser("mc-readonly-" + tag, bankA.getBankId(), Role.READ_ONLY);
        foreignAdmin = storeUser("mc-foreign-" + tag, bankB.getBankId(), Role.ADMIN);
        clientIp = "10.10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM PendingChange WHERE BankId = ? OR BankId = ?",
                bankA.getBankId(), bankB.getBankId());
        for (Integer id : createdTemplateIds) {
            templates.findById(id).ifPresent(templates::delete);
        }
        createdTemplateIds.clear();
        for (Integer id : createdUserIds) {
            refreshTokens.findByUserId(id).forEach(refreshTokens::delete);
            users.deleteById(id);
        }
        createdUserIds.clear();
        banks.delete(bankA);
        banks.delete(bankB);
    }

    private AppUser storeUser(String emailPrefix, int bankId, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(emailPrefix + "@example.com");
        entity.setPasswordHash(passwordEncoder.encode(PASSWORD));
        entity.setDisplayName(emailPrefix);
        entity.setBankId(bankId);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        createdUserIds.add(saved.getAppUserId());
        return saved;
    }

    private Template storeTemplate(String name, AppUser owner) {
        Template entity = new Template(0, name, "before");
        entity.setOwnerUserId(owner.getAppUserId());
        entity.setBankId(owner.getBankId());
        Template saved = templates.save(entity);
        createdTemplateIds.add(saved.getTemplateId());
        return saved;
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    /** A real password login, for a session whose death the tests observe. */
    private String sessionToken(AppUser user) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\""
                                + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private int submitRoleChange(AppUser maker, int targetUserId, String role) throws Exception {
        MvcResult submitted = mvc.perform(put("/api/admin/users/" + targetUserId + "/role")
                        .header("Authorization", bearer(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.changeType").value("USER_ROLE_CHANGE"))
                .andReturn();
        return MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
    }

    private String statusInDb(int pendingChangeId) {
        return jdbc.queryForObject(
                "SELECT Status FROM PendingChange WHERE PendingChangeId = ?",
                String.class, pendingChangeId);
    }

    // --------------------------------------------------------- THE B7 GATE

    @Test
    void changeStaysPendingSelfApprovalIsHardBlockedAndADifferentAdminApplies()
            throws Exception {
        // The target holds a live session.
        String memberToken = sessionToken(member);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        // MAKER: admin A submits — 202, and NOTHING has happened yet: the
        // role is unchanged and the target's tokens are alive.
        int pendingId = submitRoleChange(adminA, member.getAppUserId(), "READ_ONLY");
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getRole())
                .isEqualTo(Role.READ_WRITE);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        // MAKER == CHECKER: a hard 409 — the B7 gate.
        MvcResult refused = mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"looks fine to me\"}"))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(MAPPER.readTree(refused.getResponse().getContentAsString())
                .get("error").asText()).contains("Self-approval is blocked");
        // The attempt is evidence: SELF_APPROVAL_BLOCKED, outcome DENIED,
        // written in its own transaction so the 409 could not roll it back.
        Map<String, Object> auditRow = jdbc.queryForMap(
                "SELECT Outcome, ActorUserId FROM AuditEvent "
                        + "WHERE Action = 'SELF_APPROVAL_BLOCKED' AND EntityId = ?",
                String.valueOf(pendingId));
        assertThat(auditRow.get("Outcome")).isEqualTo("DENIED");
        assertThat(auditRow.get("ActorUserId")).isEqualTo(adminA.getAppUserId());
        // Still pending, still inert.
        assertThat(statusInDb(pendingId)).isEqualTo("REQUESTED");
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getRole())
                .isEqualTo(Role.READ_WRITE);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        // CHECKER: admin B approves — applied, audited, and the ws09 mover
        // mechanics fire: the target's pre-change token dies immediately.
        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(adminB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"verified with HR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.checkerEmail").value(adminB.getEmail()));
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getRole())
                .isEqualTo(Role.READ_ONLY);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isUnauthorized());
        Integer approvedAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'CHANGE_APPROVED' "
                        + "AND EntityId = ?", Integer.class, String.valueOf(pendingId));
        assertThat(approvedAudit).isOne();
        // The applier's own domain audit rode along in the same approval.
        Integer domainAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'USER_ROLE_CHANGED' "
                        + "AND EntityId = ?", Integer.class,
                String.valueOf(member.getAppUserId()));
        assertThat(domainAudit).isOne();

        // A decided change cannot be decided again.
        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------- reject path

    @Test
    void rejectedChangeNeverTouchesTheTarget() throws Exception {
        MvcResult submitted = mvc.perform(
                        put("/api/admin/users/" + member.getAppUserId() + "/disabled")
                                .header("Authorization", bearer(adminA))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"disabled\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("USER_DISABLE"))
                .andReturn();
        int pendingId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();

        mvc.perform(post("/api/admin/changes/" + pendingId + "/reject")
                        .header("Authorization", bearer(adminB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"person is only on leave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.checkerComment").value("person is only on leave"));

        assertThat(users.findById(member.getAppUserId()).orElseThrow().isDisabled()).isFalse();
        sessionToken(member); // the account still signs in
        Integer rejectedAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'CHANGE_REJECTED' "
                        + "AND EntityId = ?", Integer.class, String.valueOf(pendingId));
        assertThat(rejectedAudit).isOne();
    }

    // ---------------------------------------------------------- cancel path

    @Test
    void onlyTheMakerCanCancelAnUndecidedChange() throws Exception {
        int pendingId = submitRoleChange(adminA, member.getAppUserId(), "APPROVER");

        // Someone else (even an admin) cannot cancel the maker's request.
        mvc.perform(post("/api/admin/changes/" + pendingId + "/cancel")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/changes/" + pendingId + "/cancel")
                        .header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // A cancelled change is spent: approving it now is a conflict.
        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isConflict());
        assertThat(users.findById(member.getAppUserId()).orElseThrow().getRole())
                .isEqualTo(Role.READ_WRITE);
    }

    // ----------------------------------------------- listing + bank scoping

    @Test
    void approversSeeTheQueueMakersOnlyTheirOwnAndOtherBanksNothing() throws Exception {
        int pendingId = submitRoleChange(adminA, member.getAppUserId(), "READ_ONLY");

        // The APPROVER role (CHANGE_APPROVE without USER_ADMIN) reviews the queue.
        MvcResult queue = mvc.perform(get("/api/admin/changes?status=REQUESTED")
                        .header("Authorization", bearer(approver)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = MAPPER.readTree(queue.getResponse().getContentAsString());
        List<Integer> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("pendingChangeId").asInt()));
        assertThat(ids).contains(pendingId);

        // A maker without CHANGE_APPROVE (the template writer) sees only
        // their own submissions — not admin A's user change.
        MvcResult own = mvc.perform(get("/api/admin/changes")
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(own.getResponse().getContentAsString()).doesNotContain(
                "\"pendingChangeId\":" + pendingId);

        // A READ_ONLY account has no business on the queue at all.
        mvc.perform(get("/api/admin/changes").header("Authorization", bearer(readOnly)))
                .andExpect(status().isForbidden());

        // Another bank's admin: the change does not exist for them.
        mvc.perform(post("/api/admin/changes/" + pendingId + "/approve")
                        .header("Authorization", bearer(foreignAdmin)))
                .andExpect(status().isNotFound());
        MvcResult foreignQueue = mvc.perform(get("/api/admin/changes")
                        .header("Authorization", bearer(foreignAdmin)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(foreignQueue.getResponse().getContentAsString()).doesNotContain(
                "\"pendingChangeId\":" + pendingId);
        assertThat(statusInDb(pendingId)).isEqualTo("REQUESTED");
    }

    // -------------------------------------------- template pending flow (ON)

    @Test
    void templateUpdateAndDeleteGoThroughMakerCheckerWhenGated() throws Exception {
        Template template = storeTemplate("MC Before " + UUID.randomUUID(), writer);

        // UPDATE: 202, pending, template untouched.
        MvcResult submitted = mvc.perform(put("/Templates/Update")
                        .header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + template.getTemplateId()
                                + ",\"name\":\"MC After\",\"description\":\"after\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.changeType").value("TEMPLATE_UPDATE"))
                .andReturn();
        int updateId = MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        assertThat(templates.findById(template.getTemplateId()).orElseThrow().getName())
                .startsWith("MC Before");

        // The APPROVER applies it; the C7 TEMPLATE_UPDATED audit rides along.
        mvc.perform(post("/api/admin/changes/" + updateId + "/approve")
                        .header("Authorization", bearer(approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(templates.findById(template.getTemplateId()).orElseThrow().getName())
                .isEqualTo("MC After");
        Integer updatedAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'TEMPLATE_UPDATED' "
                        + "AND EntityId = ?", Integer.class,
                String.valueOf(template.getTemplateId()));
        assertThat(updatedAudit).isOne();

        // DELETE: same dance; the template survives until the approval.
        MvcResult deleteSubmitted = mvc.perform(delete("/Templates/" + template.getTemplateId())
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("TEMPLATE_DELETE"))
                .andReturn();
        int deleteId = MAPPER.readTree(deleteSubmitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
        assertThat(templates.findById(template.getTemplateId())).isPresent();

        mvc.perform(post("/api/admin/changes/" + deleteId + "/approve")
                        .header("Authorization", bearer(approver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(templates.findById(template.getTemplateId())).isEmpty();
    }

    // ------------------------------------- apply-time invariants may refuse

    @Test
    void approvedApplyReRunsInvariantsAndAFailureIsBookkeptAsFailed() throws Exception {
        String email = "mc-joiner-" + UUID.randomUUID() + "@example.com";
        String body = "{\"email\":\"" + email + "\",\"role\":\"READ_ONLY\","
                + "\"temporaryPassword\":\"" + PASSWORD + "\"}";

        // Two identical USER_CREATE requests both pass submit-time checks —
        // the account does not exist yet.
        int first = submitCreate(body);
        int second = submitCreate(body);

        mvc.perform(post("/api/admin/changes/" + first + "/approve")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isOk());
        JsonNode created = MAPPER.readTree(mvc.perform(get("/api/admin/changes?status=APPLIED")
                        .header("Authorization", bearer(adminB)))
                .andReturn().getResponse().getContentAsString());
        created.forEach(row -> {
            if (row.get("pendingChangeId").asInt() == first) {
                createdUserIds.add(Integer.parseInt(row.get("targetEntityId").asText()));
            }
        });

        // The second approval hits the re-run duplicate check: the apply
        // rolls back, the 409 reaches the checker, and the row is FAILED —
        // bookkept in its own transaction, so the rollback cannot erase it.
        mvc.perform(post("/api/admin/changes/" + second + "/approve")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isConflict());
        assertThat(statusInDb(second)).isEqualTo("FAILED");
        Integer accounts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AppUsers WHERE Email = ?", Integer.class,
                email.toLowerCase());
        assertThat(accounts).isOne();
        Integer failedAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AuditEvent WHERE Action = 'CHANGE_APPLY_FAILED' "
                        + "AND EntityId = ?", Integer.class, String.valueOf(second));
        assertThat(failedAudit).isOne();
    }

    private int submitCreate(String body) throws Exception {
        MvcResult submitted = mvc.perform(post("/api/admin/users")
                        .header("Authorization", bearer(adminA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        return MAPPER.readTree(submitted.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
    }
}
