package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.Uuid7;
import com.modocument.infrastructure.alerting.WebhookAlertChannel;
import com.modocument.infrastructure.siem.SiemEventMapper;
import com.modocument.infrastructure.siem.SiemWebhookForwarder;
import com.modocument.testsupport.WebhookStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate E6 (SEC-031): a bank's SIEM feed contains only that bank's events.
 *
 * <ul>
 *   <li>two banks' events are seeded; bank A's admin reads bank A's feed —
 *       every line is organization.id = A, all of A's seeded events are
 *       there, zero of bank B's — and vice versa;</li>
 *   <li>cursor pagination walks the feed in id order without gaps or
 *       repeats (X-Next-Cursor / X-Has-More);</li>
 *   <li>SIEM_READ is ADMIN-only;</li>
 *   <li>the push forwarder (single-bank) delivers its bank's events and
 *       never another bank's;</li>
 *   <li>PIN: the field list in docs/security/siem-event-schema.md equals the
 *       fields the serializer emits for a fully populated event.</li>
 * </ul>
 */
class SiemFeedE6Test extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SCHEMA_DOC = Path.of("docs", "security", "siem-event-schema.md");

    @Autowired
    private AuditRecorder audit;
    @Autowired
    private SiemEventMapper mapper;
    @Autowired
    private SiemWebhookForwarder forwarder;

    private Bank bankA;
    private Bank bankB;
    private AppUser adminA;
    private AppUser adminB;
    private List<String> seededA;
    private List<String> seededB;

    @BeforeEach
    void seedTwoBanks() {
        bankA = newBank("E6A");
        bankB = newBank("E6B");
        adminA = newUser(bankA, "e6-admin-a", Role.ADMIN, null);
        adminB = newUser(bankB, "e6-admin-b", Role.ADMIN, null);
        for (int i = 0; i < 5; i++) {
            record(bankA, adminA, "E6_EVENT_A" + i);
        }
        for (int i = 0; i < 3; i++) {
            record(bankB, adminB, "E6_EVENT_B" + i);
        }
        seededA = ids(bankA, "E6_EVENT_A");
        seededB = ids(bankB, "E6_EVENT_B");
        assertThat(seededA).hasSize(5);
        assertThat(seededB).hasSize(3);
    }

    private void record(Bank bank, AppUser actor, String action) {
        audit.record(AuditEventSpec.builder(action, AuditOutcome.SUCCESS)
                .actorUserId(actor.getAppUserId())
                .actorEmail(actor.getEmail())
                .actorRole(actor.getRole().name())
                .bankId(bank.getBankId())
                .entity("Template", "42")
                .newValues(Map.of("name", "e6"))
                .build());
    }

    private List<String> ids(Bank bank, String actionPrefix) {
        return jdbc.queryForList("SELECT CAST(AuditEventId AS VARCHAR) FROM AuditEvent "
                        + "WHERE BankId = ? AND Action LIKE ?",
                String.class, bank.getBankId(), actionPrefix + "%");
    }

    private record Page(List<JsonNode> events, String nextCursor, boolean hasMore) {
    }

    private Page feed(AppUser caller, String since, Integer limit) throws Exception {
        var request = get("/api/siem/events").header("Authorization", bearer(caller));
        if (since != null) {
            request = request.param("since", since);
        }
        if (limit != null) {
            request = request.param("limit", String.valueOf(limit));
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).startsWith("application/x-ndjson");
        List<JsonNode> events = new ArrayList<>();
        for (String line : result.getResponse().getContentAsString().split("\n")) {
            if (!line.isBlank()) {
                events.add(MAPPER.readTree(line));
            }
        }
        return new Page(events, result.getResponse().getHeader("X-Next-Cursor"),
                Boolean.parseBoolean(result.getResponse().getHeader("X-Has-More")));
    }

    private static List<String> eventIds(List<JsonNode> events) {
        return events.stream().map(e -> e.get("event").get("id").asText()).toList();
    }

    @Test
    void eachBanksFeedContainsOnlyThatBanksEvents() throws Exception {
        Page a = feed(adminA, null, 1000);
        assertThat(a.hasMore()).isFalse();
        assertThat(a.events()).isNotEmpty().allSatisfy(e ->
                assertThat(e.get("organization").get("id").asText())
                        .isEqualTo(String.valueOf(bankA.getBankId())));
        assertThat(eventIds(a.events())).containsAll(seededA).doesNotContainAnyElementsOf(seededB);

        Page b = feed(adminB, null, 1000);
        assertThat(b.events()).isNotEmpty().allSatisfy(e ->
                assertThat(e.get("organization").get("id").asText())
                        .isEqualTo(String.valueOf(bankB.getBankId())));
        assertThat(eventIds(b.events())).containsAll(seededB).doesNotContainAnyElementsOf(seededA);

        // The document carries the documented shape.
        JsonNode first = a.events().stream()
                .filter(e -> e.get("event").get("action").asText().startsWith("E6_EVENT_A"))
                .findFirst().orElseThrow();
        assertThat(first.get("event").get("outcome").asText()).isEqualTo("success");
        assertThat(first.get("event").get("dataset").asText()).isEqualTo("modocument.audit");
        assertThat(first.get("user").get("id").asText()).isEqualTo(String.valueOf(adminA.getAppUserId()));
        assertThat(first.get("user").get("roles").get(0).asText()).isEqualTo("ADMIN");
        assertThat(first.get("modocument").get("new_values").get("name").asText()).isEqualTo("e6");
    }

    @Test
    void cursorPaginationWalksTheFeedInOrderWithoutGapsOrRepeats() throws Exception {
        List<String> all = eventIds(feed(adminA, null, 1000).events());
        assertThat(all).containsAll(seededA);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int calls = 0;
        while (true) {
            Page page = feed(adminA, cursor, 2);
            calls++;
            assertThat(page.events().size()).isLessThanOrEqualTo(2);
            walked.addAll(eventIds(page.events()));
            if (page.events().isEmpty()) {
                // An empty page hands the same cursor back.
                assertThat(page.nextCursor()).isEqualTo(cursor);
                assertThat(page.hasMore()).isFalse();
                break;
            }
            assertThat(page.nextCursor()).isEqualTo(eventIds(page.events()).get(page.events().size() - 1));
            cursor = page.nextCursor();
            assertThat(calls).as("pagination terminates").isLessThan(100);
        }
        assertThat(walked).as("same events, same order, no repeats").isEqualTo(all);
        assertThat(new TreeSet<>(walked)).hasSameSizeAs(walked);

        // Events recorded after the walk appear after the final cursor.
        record(bankA, adminA, "E6_LATE_EVENT");
        Page tail = feed(adminA, cursor, 10);
        assertThat(tail.events()).extracting(e -> e.get("event").get("action").asText())
                .contains("E6_LATE_EVENT");
        assertThat(eventIds(tail.events())).doesNotContainAnyElementsOf(walked);
    }

    @Test
    void theFeedIsAdminOnlyAndValidatesItsParameters() throws Exception {
        AppUser writer = newUser(bankA, "e6-writer", Role.READ_WRITE, null);
        mvc.perform(get("/api/siem/events").header("Authorization", bearer(writer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/siem/events"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/siem/events").param("since", "not-a-uuid")
                        .header("Authorization", bearer(adminA)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/siem/events").param("limit", "5000")
                        .header("Authorization", bearer(adminA)))
                .andExpect(status().isBadRequest());
        // Another bank's event id as a cursor reveals nothing of that bank.
        Page page = feed(adminA, seededB.get(0), 1000);
        assertThat(page.events()).allSatisfy(e ->
                assertThat(e.get("organization").get("id").asText())
                        .isEqualTo(String.valueOf(bankA.getBankId())));
    }

    @Test
    void thePushForwarderSendsOnlyItsOwnBanksEvents() throws Exception {
        Bank push = banks.findByCode(PUSH_BANK_CODE)
                .orElseGet(() -> banks.save(new Bank(PUSH_BANK_CODE, "Push Bank")));
        forwarder.onSweep(Instant.now()); // binds the bank created above
        assertThat(forwarder.boundBankId()).contains(push.getBankId());

        AppUser pushAdmin = newUser(push, "e6-push-admin", Role.ADMIN, null);
        String marker = "E6_PUSH_" + tag().toUpperCase();
        record(push, pushAdmin, marker);
        record(bankB, adminB, marker + "_OTHER_BANK");
        String pushedId = ids(push, marker).get(0);

        await().atMost(Duration.ofSeconds(15)).until(() -> WebhookStub.get().receivedOn(SIEM_PATH)
                .stream().anyMatch(r -> r.body().contains(pushedId)));
        List<WebhookStub.Received> bodies = WebhookStub.get().receivedOn(SIEM_PATH);
        for (WebhookStub.Received received : bodies) {
            assertThat(received.header("Content-Type")).startsWith("application/x-ndjson");
            assertThat(received.header("X-MoDocument-Signature"))
                    .isEqualTo("sha256=" + WebhookAlertChannel.hmac(WEBHOOK_SECRET, received.body()));
            for (String line : received.body().split("\n")) {
                JsonNode event = MAPPER.readTree(line);
                assertThat(event.get("organization").get("id").asText())
                        .as("only the configured bank's events are pushed")
                        .isEqualTo(String.valueOf(push.getBankId()));
                assertThat(event.get("event").get("action").asText()).doesNotEndWith("_OTHER_BANK");
            }
        }
    }

    @Test
    void aFailedPushStaysQueuedAndTheMinuteSweepRetriesIt() throws Exception {
        Bank push = banks.findByCode(PUSH_BANK_CODE)
                .orElseGet(() -> banks.save(new Bank(PUSH_BANK_CODE, "Push Bank")));
        forwarder.onSweep(Instant.now());
        AppUser pushAdmin = newUser(push, "e6-retry-admin", Role.ADMIN, null);
        String marker = "E6_RETRY_" + tag().toUpperCase();

        WebhookStub.get().failNext(SIEM_PATH, 1);
        record(push, pushAdmin, marker);
        String id = ids(push, marker).get(0);

        // First attempt refused (503): the batch is back in the queue.
        await().atMost(Duration.ofSeconds(15)).until(() -> WebhookStub.get().receivedOn(SIEM_PATH)
                .stream().filter(r -> r.body().contains(id)).count() == 1
                && forwarder.pending() > 0);

        forwarder.onSweep(Instant.now());

        await().atMost(Duration.ofSeconds(15)).until(() -> WebhookStub.get().receivedOn(SIEM_PATH)
                .stream().filter(r -> r.body().contains(id)).count() == 2
                && forwarder.pending() == 0);
    }

    /**
     * PIN: docs/security/siem-event-schema.md's field table lists exactly the
     * fields a fully populated event serializes to. Renaming, adding or
     * dropping a field without the doc row (or vice versa) fails here.
     */
    @Test
    void schemaDocFieldListMatchesTheSerializedOutput() throws Exception {
        AuditEvent full = AuditEvent.builder(Uuid7.generate(), Instant.parse("2026-10-09T10:15:30Z"),
                        7, "EXPORT_BLOCKED", AuditOutcome.DENIED)
                .actor(42, "jane@bank.example", "READ_WRITE")
                .sessionJti(Uuid7.generate())
                .request("req-123", "203.0.113.9")
                .entity("IngestionJob", "17")
                .values("{\"before\":1}", "{\"rows\":10000,\"cap\":5000}")
                .build();
        Set<String> serialized = new TreeSet<>();
        collectLeaves("", mapper.toDocument(full), serialized);

        Set<String> documented = documentedFields();
        assertThat(documented).as("field table found in %s", SCHEMA_DOC).isNotEmpty();
        assertThat(serialized)
                .as("serialized fields vs. the documented schema (docs/security/siem-event-schema.md)")
                .isEqualTo(documented);
    }

    /** Leaf paths; arrays and the free-form value snapshots count as one field. */
    private static void collectLeaves(String prefix, JsonNode node, Set<String> out) {
        if (prefix.equals("modocument.old_values") || prefix.equals("modocument.new_values")
                || node.isArray() || !node.isObject()) {
            out.add(prefix);
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            collectLeaves(prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey(),
                    field.getValue(), out);
        }
    }

    /** First-column backticked names of the "Field reference" table rows. */
    private static Set<String> documentedFields() throws Exception {
        String doc = Files.readString(SCHEMA_DOC);
        int start = doc.indexOf("## Field reference");
        int end = doc.indexOf("\n## ", start + 1);
        String section = doc.substring(start, end < 0 ? doc.length() : end);
        Set<String> fields = new TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\|\\s*`([^`]+)`\\s*\\|").matcher(section);
        while (row.find()) {
            fields.add(row.group(1));
        }
        return fields;
    }
}
