package com.modocument.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.model.integration.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The integration-hub management API (ws25, contracts §3 SE4 / §7.3):
 * connections under maker/checker with write-only secrets, rename direct;
 * flows created disabled, outbound enabling approved by a SECOND admin
 * (self-approval 409), widening re-requires approval, inbound enabling
 * direct; run now / history / detail for INTEGRATION_RUN holders; preview;
 * the minimum schedule interval.
 */
class IntegrationApiTest extends IntegrationTestSupport {

    private static final String CONNECTIONS = "/api/admin/integrations/connections";
    private static final String FLOWS = "/api/admin/integrations/flows";
    private static final String RUNS = "/api/admin/integrations/runs";

    private Map<String, Object> dbConnectionRequest(String name, String host) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("type", "DATABASE");
        request.put("mode", "DIRECT");
        request.put("config", Map.of("vendor", "POSTGRESQL", "host", host, "port", "5432",
                "database", "core", "username", "reader", "sslmode", "require"));
        request.put("authType", "BASIC");
        request.put("secret", Map.of("password", "db-pass-never-shown"));
        return request;
    }

    private long approve(long changeId, Object approver) throws Exception {
        postAs((com.modocument.domain.entity.AppUser) approver, "/api/admin/changes/" + changeId + "/approve",
                null).andExpect(status().isOk());
        return changeId;
    }

    @Test
    void aNewConnectionNeedsASecondAdminAndItsSecretIsNeverReturned() throws Exception {
        var submitted = postAs(admin, CONNECTIONS, dbConnectionRequest("Core DB", "db.bank.example"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("INTEGRATION_CONNECTION_CHANGE"));
        String submitBody = submitted.andReturn().getResponse().getContentAsString();
        assertThat(submitBody).doesNotContain("db-pass-never-shown").doesNotContain("secretCiphertext");
        long changeId = json(submitted).get("pendingChangeId").asLong();
        String stored = jdbc.queryForObject("SELECT PayloadJson FROM PendingChange WHERE PendingChangeId = ?",
                String.class, changeId);
        assertThat(stored).contains("secretCiphertext").doesNotContain("db-pass-never-shown");

        // Self-approval is refused; a second admin applies it.
        postAs(admin, "/api/admin/changes/" + changeId + "/approve", null).andExpect(status().isConflict());
        assertThat(json(getAs(admin, CONNECTIONS))).isEmpty();
        approve(changeId, secondAdmin);

        var list = getAs(admin, CONNECTIONS).andExpect(status().isOk());
        String listBody = list.andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("db-pass-never-shown").doesNotContain("test:");
        JsonNode connection = json(list).get(0);
        assertThat(connection.get("name").asText()).isEqualTo("Core DB");
        assertThat(connection.get("secretSet").asBoolean()).isTrue();
        assertThat(connection.get("status").asText()).isEqualTo("UNTESTED");
        assertThat(connection.get("config").get("host").asText()).isEqualTo("db.bank.example");
        assertThat(connection.get("createdBy").asInt()).isEqualTo(admin.getAppUserId());
        assertThat(auditCount("INTEGRATION_CONNECTION_CHANGED")).isEqualTo(1);
    }

    @Test
    void renamingIsDirectButAHostChangeIsMakerChecker() throws Exception {
        long changeId = json(postAs(admin, CONNECTIONS, dbConnectionRequest("Core DB", "db.bank.example"))
                .andExpect(status().isAccepted())).get("pendingChangeId").asLong();
        approve(changeId, secondAdmin);
        JsonNode created = json(getAs(admin, CONNECTIONS)).get(0);
        long id = created.get("id").asLong();

        Map<String, Object> rename = dbConnectionRequest("Core DB (prod)", "db.bank.example");
        rename.remove("secret");
        rename.put("expectedVersion", created.get("version").asInt());
        putAs(admin, CONNECTIONS + "/" + id, rename).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Core DB (prod)"));

        Map<String, Object> move = dbConnectionRequest("Core DB (prod)", "db2.bank.example");
        move.remove("secret");
        putAs(admin, CONNECTIONS + "/" + id, move).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("INTEGRATION_CONNECTION_CHANGE"));
        assertThat(json(getAs(admin, CONNECTIONS + "/" + id)).get("pendingChangeId").isNumber()).isTrue();

        Map<String, Object> stale = dbConnectionRequest("x", "db.bank.example");
        stale.put("expectedVersion", 999);
        putAs(admin, CONNECTIONS + "/" + id, stale).andExpect(status().isConflict());
    }

    @Test
    void invalidConnectionsAreRefusedAtSubmit() throws Exception {
        Map<String, Object> raw = dbConnectionRequest("Bad", "db.bank.example");
        raw.put("config", Map.of("vendor", "POSTGRESQL", "host", "db", "database", "core",
                "socketFactory", "evil.Factory"));
        postAs(admin, CONNECTIONS, raw).andExpect(status().isBadRequest());
        Map<String, Object> secretInConfig = dbConnectionRequest("Bad", "db.bank.example");
        secretInConfig.put("config", Map.of("vendor", "POSTGRESQL", "host", "db", "database", "core",
                "password", "oops"));
        postAs(admin, CONNECTIONS, secretInConfig).andExpect(status().isBadRequest());
        // A forged ciphertext never reaches the queue: the typed endpoint has
        // no such field, the generic change endpoint refuses it.
        Map<String, Object> forged = dbConnectionRequest("Forged", "db.bank.example");
        forged.put("secretCiphertext", "test:1:xxx");
        long changeId = json(postAs(admin, CONNECTIONS, forged).andExpect(status().isAccepted()))
                .get("pendingChangeId").asLong();
        assertThat(jdbc.queryForObject("SELECT PayloadJson FROM PendingChange WHERE PendingChangeId = ?",
                String.class, changeId)).doesNotContain("test:1:xxx");
        postAs(admin, "/api/admin/changes", Map.of("changeType", "INTEGRATION_CONNECTION_CHANGE",
                "payload", forged)).andExpect(status().isBadRequest());
    }

    @Test
    void testingADirectConnectionRecordsTheResult() throws Exception {
        IntegrationConnection rest = restConnection("api", "BEARER", Map.of("token", "t-secret"),
                ConnectionType.REST_API);
        postAs(admin, CONNECTIONS + "/" + rest.getId() + "/test", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        JsonNode view = json(getAs(admin, CONNECTIONS + "/" + rest.getId()));
        assertThat(view.get("status").asText()).isEqualTo("OK");
        assertThat(view.get("lastTestAt").isNull()).isFalse();
    }

    @Test
    void outboundEnablingNeedsASecondAdminAndWideningAsksAgain() throws Exception {
        IntegrationConnection hook = restConnection("hook", "HMAC_SIGNATURE",
                Map.of("hmacSecret", "0123456789abcdef0123"), ConnectionType.WEBHOOK);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "send status");
        request.put("direction", "OUTBOUND");
        request.put("connectionId", hook.getId());
        request.put("templateId", templateId);
        request.put("trigger", "EVENT");
        request.put("events", List.of("INGESTION_VALIDATED"));
        request.put("payloadKinds", List.of("JOB_STATUS"));
        request.put("targetSpec", Map.of("path", "events"));
        JsonNode created = json(postAs(admin, FLOWS, request).andExpect(status().isCreated()));
        long flowId = created.get("id").asLong();
        assertThat(created.get("enabled").asBoolean()).isFalse();
        assertThat(created.get("maskPii").asBoolean()).isTrue();
        assertThat(created.get("connectionName").asText()).isEqualTo("hook");

        JsonNode change = json(postAs(admin, FLOWS + "/" + flowId + "/enable", null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("INTEGRATION_FLOW_ENABLE")));
        long changeId = change.get("pendingChangeId").asLong();
        postAs(admin, "/api/admin/changes/" + changeId + "/approve", null).andExpect(status().isConflict());
        assertThat(json(getAs(admin, FLOWS + "/" + flowId)).get("enabled").asBoolean()).isFalse();
        approve(changeId, secondAdmin);
        JsonNode enabled = json(getAs(admin, FLOWS + "/" + flowId));
        assertThat(enabled.get("enabled").asBoolean()).isTrue();
        assertThat(auditCount("INTEGRATION_FLOW_ENABLED")).isEqualTo(1);

        // Turning masking off on the enabled outbound flow disables it and asks again.
        request.put("maskPii", false);
        request.put("expectedVersion", enabled.get("version").asInt());
        putAs(admin, FLOWS + "/" + flowId, request).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeType").value("INTEGRATION_FLOW_ENABLE"));
        JsonNode widened = json(getAs(admin, FLOWS + "/" + flowId));
        assertThat(widened.get("enabled").asBoolean()).isFalse();
        assertThat(widened.get("maskPii").asBoolean()).isFalse();
        assertThat(widened.get("pendingChangeId").isNumber()).isTrue();
        assertThat(auditCount("INTEGRATION_FLOW_DISABLED")).isEqualTo(1);

        // A disabled outbound flow cannot be run by hand.
        postAs(writer, FLOWS + "/" + flowId + "/run", null).andExpect(status().isConflict());
    }

    @Test
    void inboundFlowsEnableDirectlyRunAndPreview() throws Exception {
        stubResponses.put("/bank/customers", "{\"data\":[{\"customer_name\":\"Ada Lovelace\","
                + "\"account_no\":\"123456789\",\"amount\":\"10\"}]}");
        IntegrationConnection rest = restConnection("api", "BEARER", Map.of("token", "t"),
                ConnectionType.REST_API);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "pull customers");
        request.put("direction", "INBOUND");
        request.put("connectionId", rest.getId());
        request.put("templateId", templateId);
        request.put("trigger", "SCHEDULE");
        request.put("cron", "0 */15 * * * *");
        request.put("sourceSpec", Map.of("path", "customers", "recordsPath", "data"));
        request.put("fieldMapping", List.of(Map.of("from", "customer_name", "to", "CustomerName"),
                Map.of("from", "account_no", "to", "AccountNo"), Map.of("from", "amount", "to", "Amount")));
        long flowId = json(postAs(admin, FLOWS, request).andExpect(status().isCreated())).get("id").asLong();

        JsonNode preview = json(postAs(admin, FLOWS + "/" + flowId + "/preview", null).andExpect(status().isOk()));
        assertThat(preview.get("rows")).hasSize(1);
        assertThat(preview.get("columns").toString()).contains("customer_name");
        assertThat(preview.get("suggestedMapping").toString()).contains("CustomerName");

        postAs(admin, FLOWS + "/" + flowId + "/enable", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());

        // READ_WRITE may run flows and read runs, not manage connections.
        getAs(writer, CONNECTIONS).andExpect(status().isForbidden());
        JsonNode run = json(postAs(writer, FLOWS + "/" + flowId + "/run", null).andExpect(status().isAccepted()));
        long runId = run.get("id").asLong();
        JsonNode listed = json(getAs(writer, RUNS + "?flowId=" + flowId).andExpect(status().isOk()));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(listed.get(0).get("trigger").asText()).isEqualTo("MANUAL");
        JsonNode detail = json(getAs(writer, RUNS + "/" + runId).andExpect(status().isOk()));
        assertThat(detail.get("ingestionJobId").isNumber()).isTrue();
        assertThat(detail.get("events").toString()).contains("ingestion job");
        assertThat(json(getAs(writer, RUNS + "?status=FAILED"))).isEmpty();

        postAs(admin, FLOWS + "/" + flowId + "/disable", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        deleteAs(admin, FLOWS + "/" + flowId).andExpect(status().isNoContent());
        deleteAs(admin, CONNECTIONS + "/" + rest.getId()).andExpect(status().isNoContent());
    }

    @Test
    void schedulesFasterThanTheMinimumIntervalAndUnsafeSpecsAreRefused() throws Exception {
        IntegrationConnection rest = restConnection("api", "NONE", null, ConnectionType.REST_API);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "too often");
        request.put("direction", "INBOUND");
        request.put("connectionId", rest.getId());
        request.put("templateId", templateId);
        request.put("trigger", "SCHEDULE");
        request.put("cron", "0 * * * * *");
        request.put("sourceSpec", Map.of("path", "customers"));
        postAs(admin, FLOWS, request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("minimum interval")));

        request.put("cron", "0 0 * * * *");
        request.put("sourceSpec", Map.of("path", "../../admin"));
        postAs(admin, FLOWS, request).andExpect(status().isBadRequest());

        request.put("sourceSpec", Map.of("path", "customers"));
        request.put("direction", "INBOUND");
        request.put("templateId", 999_999);
        postAs(admin, FLOWS, request).andExpect(status().isNotFound());
    }

    private int auditCount(String action) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE BankId = ? AND Action = ?",
                Integer.class, bank.getBankId(), action);
        return count == null ? 0 : count;
    }
}
