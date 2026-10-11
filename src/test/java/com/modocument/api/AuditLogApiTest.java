package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.testsupport.PlatformBankFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ws28 audit viewer API (contracts §3) on the H2 schema: filters,
 * keyset paging, per-role masking, the CSV export, the action catalogue,
 * cross-bank isolation (another bank's ids, cursors and filters find
 * nothing), the platform routes and the viewer's own audit. RLS isolation
 * on the real schema: {@code AuditLogRlsPgIT}.
 */
class AuditLogApiTest extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PII_EMAIL = "jane.doe@customer.example";
    private static final String PII_NIC = "199012345678";

    @Autowired
    private AuditRecorder audit;

    private Bank bankA;
    private Bank bankB;
    private AppUser adminA;
    private AppUser approverA;
    private AppUser writerA;
    private AppUser adminB;

    @BeforeEach
    void seed() {
        bankA = newBank("AUA");
        bankB = newBank("AUB");
        adminA = newUser(bankA, "au-admin-a", Role.ADMIN, null);
        approverA = newUser(bankA, "au-approver-a", Role.APPROVER, null);
        writerA = newUser(bankA, "au-writer-a", Role.READ_WRITE, null);
        adminB = newUser(bankB, "au-admin-b", Role.ADMIN, null);
        for (int i = 0; i < 5; i++) {
            record(bankA, writerA, "AU_SEEDED", AuditOutcome.SUCCESS, "Template", "t" + i,
                    Map.of("i", i));
        }
        record(bankA, adminA, "AU_SEEDED_DENIED", AuditOutcome.DENIED, "IngestionJob", "77",
                null);
        record(bankA, writerA, "AU_PII", AuditOutcome.SUCCESS, "IngestionJob", "88",
                Map.of("email", PII_EMAIL, "note", "NIC " + PII_NIC + " on file"));
        record(bankB, adminB, "AU_SEEDED", AuditOutcome.SUCCESS, "Template", "b-only", null);
    }

    private void record(Bank bank, AppUser actor, String action, AuditOutcome outcome,
                        String entityType, String entityId, Object newValues) {
        audit.record(AuditEventSpec.builder(action, outcome)
                .actorUserId(actor.getAppUserId())
                .actorRole(actor.getRole().name())
                .bankId(bank.getBankId())
                .entity(entityType, entityId)
                .newValues(newValues)
                .build());
    }

    private JsonNode getJson(AppUser caller, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request.header("Authorization", bearer(caller)))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> actions(JsonNode page) {
        List<String> actions = new ArrayList<>();
        page.get("events").forEach(e -> actions.add(e.get("action").asText()));
        return actions;
    }

    @Test
    void listsTheCallersBankNewestFirstWithTheContractShape() throws Exception {
        JsonNode page = getJson(adminA, get("/api/admin/audit").param("action", "AU_SEEDED",
                "AU_SEEDED_DENIED", "AU_PII"));

        assertThat(actions(page)).containsExactlyInAnyOrder("AU_PII", "AU_SEEDED_DENIED",
                "AU_SEEDED", "AU_SEEDED", "AU_SEEDED", "AU_SEEDED", "AU_SEEDED");
        List<Instant> times = new ArrayList<>();
        page.get("events").forEach(e -> times.add(Instant.parse(e.get("occurredAt").asText())));
        for (int i = 1; i < times.size(); i++) {
            assertThat(times.get(i)).as("newest first").isBeforeOrEqualTo(times.get(i - 1));
        }
        assertThat(page.get("nextCursor").isNull()).isTrue();
        JsonNode first = null;
        for (JsonNode event : page.get("events")) {
            if (event.get("action").asText().equals("AU_PII")) {
                first = event;
            }
        }
        assertThat(first).isNotNull();
        for (String field : List.of("id", "occurredAt", "action", "outcome", "actorUserId",
                "actorEmail", "actorRole", "entityType", "entityId", "ip", "requestId",
                "oldValues", "newValues")) {
            assertThat(first.has(field)).as(field).isTrue();
        }
        assertThat(first.get("actorUserId").asInt()).isEqualTo(writerA.getAppUserId());
        assertThat(first.get("actorEmail").asText()).as("resolved from the bank's users")
                .isEqualTo(writerA.getEmail());
        assertThat(first.get("newValues").isObject()).as("values are objects").isTrue();
        assertThat(page.toString()).doesNotContain("b-only");
    }

    @Test
    void filtersNarrowByOutcomeEntityActorAndTime() throws Exception {
        assertThat(actions(getJson(adminA, get("/api/admin/audit").param("outcome", "DENIED")
                .param("action", "AU_SEEDED_DENIED,AU_SEEDED"))))
                .containsExactly("AU_SEEDED_DENIED");
        assertThat(actions(getJson(adminA, get("/api/admin/audit")
                .param("entityType", "Template").param("entityId", "t3"))))
                .containsExactly("AU_SEEDED");
        assertThat(actions(getJson(adminA, get("/api/admin/audit").param("action", "AU_SEEDED")
                .param("actor", String.valueOf(adminA.getAppUserId()))))).isEmpty();
        assertThat(actions(getJson(adminA, get("/api/admin/audit").param("action", "AU_PII")
                .param("actor", writerA.getEmail().toUpperCase())))).containsExactly("AU_PII");
        String future = Instant.now().plusSeconds(3600).toString();
        assertThat(actions(getJson(adminA, get("/api/admin/audit").param("from", future))))
                .isEmpty();
        assertThat(actions(getJson(adminA, get("/api/admin/audit").param("action", "AU_PII")
                .param("to", future)))).containsExactly("AU_PII");

        for (String[] bad : List.of(new String[]{"limit", "0"}, new String[]{"limit", "201"},
                new String[]{"outcome", "MAYBE"}, new String[]{"from", "yesterday"},
                new String[]{"cursor", "not-a-cursor"}, new String[]{"action", "drop table"})) {
            mvc.perform(get("/api/admin/audit").param(bad[0], bad[1])
                            .header("Authorization", bearer(adminA)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void keysetPagingWalksEveryRowOnceNewestFirst() throws Exception {
        List<String> walked = new ArrayList<>();
        String cursor = null;
        int calls = 0;
        do {
            MockHttpServletRequestBuilder request = get("/api/admin/audit")
                    .param("action", "AU_SEEDED", "AU_SEEDED_DENIED", "AU_PII")
                    .param("limit", "2");
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode page = getJson(adminA, request);
            assertThat(page.get("events").size()).isLessThanOrEqualTo(2);
            page.get("events").forEach(e -> walked.add(e.get("id").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            calls++;
        } while (cursor != null && calls < 20);

        assertThat(walked).hasSize(7);
        assertThat(new HashSet<>(walked)).hasSize(7);
        JsonNode all = getJson(adminA, get("/api/admin/audit")
                .param("action", "AU_SEEDED", "AU_SEEDED_DENIED", "AU_PII"));
        List<String> expected = new ArrayList<>();
        all.get("events").forEach(e -> expected.add(e.get("id").asText()));
        assertThat(walked).isEqualTo(expected);
    }

    @Test
    void anotherBanksIdsCursorsAndFiltersFindNothing() throws Exception {
        JsonNode pageB = getJson(adminB, get("/api/admin/audit").param("action", "AU_SEEDED")
                .param("limit", "1"));
        assertThat(actions(pageB)).containsExactly("AU_SEEDED");
        assertThat(pageB.get("events").get(0).get("entityId").asText()).isEqualTo("b-only");

        // Bank A filtering for bank B's entity: empty, not a 403 that confirms it exists.
        assertThat(actions(getJson(adminA, get("/api/admin/audit").param("entityId", "b-only"))))
                .isEmpty();
        // Bank B's actor id names nobody in bank A.
        assertThat(actions(getJson(adminA, get("/api/admin/audit")
                .param("actor", String.valueOf(adminB.getAppUserId()))))).isEmpty();
        // A cursor taken from bank B's walk only positions bank A's own walk.
        JsonNode bWithCursor = getJson(adminB, get("/api/admin/audit").param("limit", "1"));
        if (!bWithCursor.get("nextCursor").isNull()) {
            JsonNode a = getJson(adminA, get("/api/admin/audit")
                    .param("cursor", bWithCursor.get("nextCursor").asText()));
            assertThat(a.toString()).doesNotContain("b-only");
            a.get("events").forEach(e -> assertThat(e.get("actorUserId").asInt())
                    .isNotEqualTo(adminB.getAppUserId()));
        }
    }

    @Test
    void valuesAreMaskedForReadOnlyReadersAndInEveryExport() throws Exception {
        JsonNode admin = getJson(adminA, get("/api/admin/audit").param("action", "AU_PII"));
        assertThat(admin.get("events").get(0).get("newValues").get("email").asText())
                .as("ADMIN reads like the working screens (raw by default)").isEqualTo(PII_EMAIL);

        JsonNode approver = getJson(approverA, get("/api/admin/audit").param("action", "AU_PII"));
        JsonNode values = approver.get("events").get(0).get("newValues");
        assertThat(values.get("email").asText()).isEqualTo("j***@customer.example");
        assertThat(values.get("note").asText()).doesNotContain(PII_NIC);
        assertThat(approver.toString()).doesNotContain(PII_EMAIL);

        MvcResult csv = mvc.perform(get("/api/admin/audit/export").param("action", "AU_PII")
                        .header("Authorization", bearer(adminA)))
                .andExpect(status().isOk()).andReturn();
        String body = csv.getResponse().getContentAsString();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getHeader("Content-Disposition"))
                .contains("audit-log.csv");
        assertThat(csv.getResponse().getHeader("X-Export-Total")).isEqualTo("1");
        assertThat(csv.getResponse().getHeader("X-Export-Truncated")).isEqualTo("false");
        String[] lines = body.split("\r\n");
        assertThat(lines[0]).startsWith("occurredAt,id,action,outcome,actorUserId");
        assertThat(lines).hasSize(2);
        assertThat(body).contains("AU_PII").doesNotContain(PII_EMAIL).doesNotContain(PII_NIC);
        assertThat(auditRows(bankA, "AUDIT_LOG_EXPORTED")).singleElement()
                .satisfies(row -> assertThat(row.newValues()).contains("\"rowCount\":1"));
    }

    @Test
    void viewingIsAuditedOncePerActorWithinTheWindow() throws Exception {
        AppUser viewer = newUser(bankA, "au-viewer", Role.ADMIN, null);
        getJson(viewer, get("/api/admin/audit"));
        getJson(viewer, get("/api/admin/audit").param("outcome", "DENIED"));
        getJson(viewer, get("/api/admin/audit").param("limit", "3"));

        Integer views = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? "
                        + "AND Action = 'AUDIT_LOG_VIEWED' AND ActorUserId = ?", Integer.class,
                bankA.getBankId(), viewer.getAppUserId());
        assertThat(views).isOne();
    }

    @Test
    void theActionCatalogueGroupsActionsForTheFilterPicker() throws Exception {
        JsonNode catalogue = getJson(approverA, get("/api/admin/audit/actions"));
        Set<String> names = new HashSet<>();
        catalogue.get("actions").forEach(a -> {
            names.add(a.get("name").asText());
            assertThat(a.get("category").asText()).isNotBlank();
        });
        assertThat(names).contains("TEMPLATE_CREATED", "ACCESS_DENIED", "SENSITIVE_DATA_VIEWED",
                "AUDIT_LOG_VIEWED", "LOGIN_FAILED", "EXPORT_RUN");
    }

    @Test
    void auditReadIsForAdminsApproversAndPlatformAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/audit").header("Authorization", bearer(writerA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit/export").header("Authorization", bearer(writerA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit")).andExpect(status().isUnauthorized());
        // A customer bank's ADMIN holds AUDIT_READ but is not in the platform bank.
        mvc.perform(get("/api/platform/audit").header("Authorization", bearer(adminA)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/audit/counts").header("Authorization", bearer(adminA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePlatformSeesItsOwnEventsAndOtherBanksAsCountsOnly() throws Exception {
        Bank platform = PlatformBankFixture.ensurePlatformBank(banks);
        AppUser platformAdmin = PlatformBankFixture.user(users, platform.getBankId(),
                Role.PLATFORM_ADMIN, "au-platform");
        record(platform, platformAdmin, "AU_PLATFORM_OWN", AuditOutcome.SUCCESS, "Bank",
                String.valueOf(bankA.getBankId()), null);

        JsonNode own = getJson(platformAdmin, get("/api/platform/audit")
                .param("action", "AU_PLATFORM_OWN", "AU_SEEDED"));
        assertThat(actions(own)).containsExactly("AU_PLATFORM_OWN");
        // The admin routes are scoped to the caller's bank too: the platform bank.
        JsonNode adminRoute = getJson(platformAdmin, get("/api/admin/audit")
                .param("action", "AU_PLATFORM_OWN", "AU_SEEDED"));
        assertThat(actions(adminRoute)).containsExactly("AU_PLATFORM_OWN");
        mvc.perform(get("/api/admin/audit/export").param("action", "AU_SEEDED")
                        .header("Authorization", bearer(platformAdmin)))
                .andExpect(status().isOk());

        JsonNode counts = getJson(platformAdmin, get("/api/platform/audit/counts"));
        JsonNode a = null;
        JsonNode b = null;
        for (JsonNode bank : counts.get("banks")) {
            if (bank.get("bankId").asInt() == bankA.getBankId()) {
                a = bank;
            }
            if (bank.get("bankId").asInt() == bankB.getBankId()) {
                b = bank;
            }
            assertThat(bank.size()).as("counts only").isEqualTo(5);
        }
        assertThat(a).isNotNull();
        assertThat(a.get("code").asText()).isEqualTo(bankA.getCode());
        assertThat(a.get("total").asLong()).isGreaterThanOrEqualTo(7);
        assertThat(a.get("denied").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(b.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.toString()).doesNotContain("b-only").doesNotContain(PII_EMAIL);

        mvc.perform(get("/api/platform/audit/counts").param("from", "2020-01-01")
                        .param("to", "2026-10-01").header("Authorization", bearer(platformAdmin)))
                .andExpect(status().isBadRequest());
    }
}
