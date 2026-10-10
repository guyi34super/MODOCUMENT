package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.BankEgressRule;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.integration.EgressRuleStatus;
import com.modocument.infrastructure.integration.egress.BankEgressGuard;
import com.modocument.infrastructure.net.EgressTargets;
import com.modocument.infrastructure.net.OutboundHostPolicy;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankEgressRuleJpaRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws25 egress rules end to end (contracts §3 SE1, §2 EGRESS_RULE_APPROVE):
 * a bank's integration admin requests a private destination, two platform
 * admins approve it (maker/checker), the bank-aware guard then opens exactly
 * that destination for exactly that bank, and the bank revokes it directly.
 * Wrong-bank ids answer 404; non-admins and other roles 403.
 *
 * <p>Committed data (the platform approval writes in its own transaction);
 * every row is removed afterwards except the append-only audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EgressRuleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_ROUTE = "/api/admin/integrations/egress-rules";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private BankEgressRuleJpaRepository rules;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Integer> createdUsers = new ArrayList<>();
    private Bank platform;
    private Bank bankA;
    private Bank bankB;
    private AppUser platformMaker;
    private AppUser platformChecker;
    private AppUser adminA;
    private AppUser memberA;
    private AppUser adminB;

    @BeforeEach
    void seed() {
        platform = PlatformBankFixture.ensurePlatformBank(banks);
        String tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        bankA = banks.save(new Bank("EGA" + tag, "Egress A " + tag));
        bankB = banks.save(new Bank("EGB" + tag, "Egress B " + tag));
        platformMaker = user(platform, Role.PLATFORM_ADMIN);
        platformChecker = user(platform, Role.PLATFORM_ADMIN);
        adminA = user(bankA, Role.ADMIN);
        memberA = user(bankA, Role.READ_WRITE);
        adminB = user(bankB, Role.ADMIN);
    }

    @AfterEach
    void cleanUp() {
        for (Integer id : createdUsers) {
            jdbc.update("DELETE FROM PendingChange WHERE MakerUserId = ?", id);
        }
        jdbc.update("DELETE FROM BankEgressRule WHERE BankId IN (?, ?)",
                bankA.getBankId(), bankB.getBankId());
        createdUsers.forEach(users::deleteById);
        createdUsers.clear();
        banks.deleteById(bankA.getBankId());
        banks.deleteById(bankB.getBankId());
    }

    @Test
    void aRequestedDestinationOpensOnlyAfterTwoPlatformAdminsApproveAndUntilRevoked()
            throws Exception {
        BankEgressGuard guard = enforcingGuard();
        assertThatThrownBy(() -> guard.checkDestination(bankA.getBankId(), "core-db.bank-a.lan", 5432))
                .isInstanceOf(SecurityException.class);

        // ---- the bank requests (PENDING opens nothing) ---------------------
        JsonNode created = send(post(ADMIN_ROUTE), adminA,
                "{\"host\":\"Core-DB.Bank-A.lan\",\"port\":5432,\"reason\":\"core banking DB over VPN\"}",
                200);
        int ruleId = created.get("id").asInt();
        assertThat(created.get("host").asText()).isEqualTo("core-db.bank-a.lan");
        assertThat(created.get("cidr").isNull()).isTrue();
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        assertThat(created.get("requestedBy").asInt()).isEqualTo(adminA.getAppUserId());
        assertThat(created.get("approvedBy").isNull()).isTrue();
        assertThat(created.get("bankCode").asText()).isEqualTo(bankA.getCode());
        assertThat(created.get("bankName").asText()).isEqualTo(bankA.getName());
        assertThat(created.get("reason").asText()).isEqualTo("core banking DB over VPN");
        assertThat(created.hasNonNull("createdAt")).isTrue();
        assertThat(auditCount("EGRESS_RULE_REQUESTED", bankA.getBankId(), ruleId)).isOne();
        assertThatThrownBy(() -> guard.checkDestination(bankA.getBankId(), "core-db.bank-a.lan", 5432))
                .isInstanceOf(SecurityException.class);

        // The same destination twice is a conflict; bank B sees nothing of A's.
        send(post(ADMIN_ROUTE), adminA,
                "{\"host\":\"core-db.bank-a.lan\",\"port\":5432,\"reason\":\"again\"}", 409);
        assertThat(send(get(ADMIN_ROUTE), adminA, null, 200)).hasSize(1);
        assertThat(send(get(ADMIN_ROUTE), adminB, null, 200)).isEmpty();

        // ---- the platform sees it across banks -----------------------------
        JsonNode listed = find(send(get("/api/platform/egress-rules"), platformMaker, null, 200),
                ruleId);
        assertThat(listed).isNotNull();
        assertThat(listed.get("bankId").asInt()).isEqualTo(bankA.getBankId());
        assertThat(listed.get("pendingChangeId").isNull()).isTrue();

        // ---- maker/checker approval ----------------------------------------
        JsonNode change = send(post("/api/platform/egress-rules/" + ruleId + "/approve"),
                platformMaker, null, 202);
        assertThat(change.get("changeType").asText()).isEqualTo("EGRESS_RULE_APPROVE");
        assertThat(change.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(change.get("payload").get("ruleId").asInt()).isEqualTo(ruleId);
        int pendingId = change.get("pendingChangeId").asInt();
        // A second request for the same rule waits on the first.
        send(post("/api/platform/egress-rules/" + ruleId + "/approve"), platformChecker, null, 409);
        assertThat(find(send(get("/api/platform/egress-rules"), platformMaker, null, 200), ruleId)
                .get("pendingChangeId").asInt()).isEqualTo(pendingId);
        // Still closed while the change waits; the maker cannot approve it.
        assertThat(rules.findById(ruleId).orElseThrow().getStatus())
                .isEqualTo(EgressRuleStatus.PENDING);
        send(post("/api/admin/changes/" + pendingId + "/approve"), platformMaker, null, 409);

        JsonNode applied = send(post("/api/admin/changes/" + pendingId + "/approve"),
                platformChecker, null, 200);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        BankEgressRule approved = rules.findById(ruleId).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(EgressRuleStatus.APPROVED);
        assertThat(approved.getApprovedByUserId()).isEqualTo(platformChecker.getAppUserId());
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(auditCount("EGRESS_RULE_APPROVED", bankA.getBankId(), ruleId)).isOne();
        assertThat(auditCount("EGRESS_RULE_APPROVED", platform.getBankId(), ruleId)).isOne();

        // ---- the guard now opens it, for bank A and that port only ----------
        assertThatCode(() -> guard.checkDestination(bankA.getBankId(), "core-db.bank-a.lan", 5432))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.checkDestination(bankA.getBankId(), "core-db.bank-a.lan", 1433))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> guard.checkDestination(bankB.getBankId(), "core-db.bank-a.lan", 5432))
                .isInstanceOf(SecurityException.class);

        // An approved rule is not approvable again.
        send(post("/api/platform/egress-rules/" + ruleId + "/approve"), platformMaker, null, 409);

        // ---- revocation: direct, own bank only ------------------------------
        send(delete(ADMIN_ROUTE + "/" + ruleId), adminB, null, 404);
        JsonNode revoked = send(post(ADMIN_ROUTE + "/" + ruleId + "/revoke"), adminA, null, 200);
        assertThat(revoked.get("status").asText()).isEqualTo("REVOKED");
        assertThat(auditCount("EGRESS_RULE_REVOKED", bankA.getBankId(), ruleId)).isOne();
        assertThatThrownBy(() -> guard.checkDestination(bankA.getBankId(), "core-db.bank-a.lan", 5432))
                .isInstanceOf(SecurityException.class);
        // Revoked rules leave the platform's default listing.
        assertThat(find(send(get("/api/platform/egress-rules"), platformMaker, null, 200), ruleId))
                .isNull();
        assertThat(find(send(get("/api/platform/egress-rules?status=REVOKED"), platformMaker,
                null, 200), ruleId)).isNotNull();
    }

    @Test
    void cidrRequestsAreCanonicalAndMalformedRequestsAreRefused() throws Exception {
        JsonNode created = send(post(ADMIN_ROUTE), adminA,
                "{\"cidr\":\"10.40.7.9/16\",\"port\":22,\"reason\":\"SFTP drop zone\"}", 200);
        assertThat(created.get("cidr").asText()).isEqualTo("10.40.0.0/16");
        assertThat(created.get("host").isNull()).isTrue();

        for (String bad : List.of(
                "{\"port\":22,\"reason\":\"neither\"}",
                "{\"host\":\"a.example\",\"cidr\":\"10.0.0.0/8\",\"port\":22,\"reason\":\"both\"}",
                "{\"host\":\"https://a.example/x\",\"port\":443,\"reason\":\"url\"}",
                "{\"host\":\"169.254.169.254\",\"port\":80,\"reason\":\"metadata\"}",
                "{\"cidr\":\"0.0.0.0/0\",\"port\":443,\"reason\":\"everything\"}",
                "{\"host\":\"a.example\",\"port\":0,\"reason\":\"port\"}",
                "{\"host\":\"a.example\",\"port\":70000,\"reason\":\"port\"}",
                "{\"host\":\"a.example\",\"port\":443,\"reason\":\"\"}")) {
            send(post(ADMIN_ROUTE), adminA, bad, 400);
        }
        send(get("/api/platform/egress-rules?status=BOGUS"), platformMaker, null, 400);
    }

    @Test
    void onlyIntegrationAdminsAndPlatformAdminsReachTheirRoutes() throws Exception {
        send(get(ADMIN_ROUTE), memberA, null, 403);
        send(post(ADMIN_ROUTE), memberA,
                "{\"host\":\"a.example\",\"port\":443,\"reason\":\"x\"}", 403);
        send(get("/api/platform/egress-rules"), adminA, null, 403);
        send(post("/api/platform/egress-rules/1/approve"), adminA, null, 403);
        // The generic queue is no shortcut: a bank admin lacks platform:banks.
        send(post("/api/admin/changes"), adminA,
                "{\"changeType\":\"EGRESS_RULE_APPROVE\",\"payload\":{\"ruleId\":1}}", 403);
        // Platform admins do not manage a bank's rules.
        send(get(ADMIN_ROUTE), platformMaker, null, 403);
        // Unknown rule: 404 at submit.
        send(post("/api/platform/egress-rules/999999/approve"), platformMaker, null, 404);
    }

    // ------------------------------------------------------------- plumbing

    /** The real repository and transactions, with the SSRF policy ON and stub DNS. */
    private BankEgressGuard enforcingGuard() {
        return new BankEgressGuard(OutboundHostPolicy.enforcing(), rules, transactionManager,
                Duration.ZERO, this::lookup, Clock.systemUTC());
    }

    private InetAddress[] lookup(String host) throws UnknownHostException {
        if (host.equals("core-db.bank-a.lan")) {
            return new InetAddress[]{EgressTargets.literal("10.40.1.20")};
        }
        throw new UnknownHostException(host);
    }

    private static JsonNode find(JsonNode list, int ruleId) {
        for (JsonNode rule : list) {
            if (rule.get("id").asInt() == ruleId) {
                return rule;
            }
        }
        return null;
    }

    private JsonNode send(MockHttpServletRequestBuilder request, AppUser caller, String json,
                          int expectedStatus) throws Exception {
        AppUser fresh = users.findById(caller.getAppUserId()).orElseThrow();
        request.header("Authorization", "Bearer " + jwtService.issueToken(fresh));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String body = mvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
    }

    private AppUser user(Bank bank, Role role) {
        AppUser user = PlatformBankFixture.user(users, bank.getBankId(), role, "ws25-egress");
        createdUsers.add(user.getAppUserId());
        return user;
    }

    private int auditCount(String action, int bankId, int ruleId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = ? "
                        + "AND BankId = ? AND EntityType = 'BankEgressRule' AND EntityId = ?",
                Integer.class, action, bankId, String.valueOf(ruleId));
        return count == null ? 0 : count;
    }
}
