package com.modocument.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.audit.JpaAuditRecorder;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * THE E9 GATE: one authenticated action is traceable end-to-end by a single
 * request id — the response echoes the inbound X-Request-Id, every log line
 * emitted while handling it carries the same requestId/userId/bankId on the
 * MDC, and the AuditEvent row written by the action carries the same
 * requestId, user and bank. Also proves the AUTHORIZATION_DENIED capture
 * path (method security's event publisher → audit listener) and that an
 * unsafe inbound request id is discarded for a minted UUIDv7.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestTraceabilityE9Test {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private AppUser writer;
    private AppUser readOnly;
    private Template template;
    private ListAppender<ILoggingEvent> auditLogLines;

    @BeforeEach
    void seedUserTemplateAndLogCapture() {
        writer = user(Role.READ_WRITE);
        readOnly = user(Role.READ_ONLY);
        Template entity = new Template(0, "E9 Old Name", "before");
        entity.setOwnerUserId(writer.getAppUserId());
        entity.setBankId(writer.getBankId());
        template = templates.save(entity);

        auditLogLines = new ListAppender<>();
        auditLogLines.start();
        auditLogger().addAppender(auditLogLines);
    }

    @AfterEach
    void cleanUp() {
        auditLogger().detachAppender(auditLogLines);
        templates.deleteById(template.getTemplateId());
        users.deleteById(writer.getAppUserId());
        users.deleteById(readOnly.getAppUserId());
        // H2 has no append-only guard (that is PostgreSQL's V14 trigger/REVOKE,
        // proven by AuditAppendOnlyPgIT); scrub so other suites see no rows.
        jdbc.update("DELETE FROM AuditEvent");
    }

    @Test
    void oneActionCarriesTheSameRequestIdUserAndBankEverywhere() throws Exception {
        String requestId = "e9-trace-" + UUID.randomUUID();

        mvc.perform(put("/Templates/Update")
                        .header("Authorization", "Bearer " + jwtService.issueToken(writer))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + template.getTemplateId()
                                + ",\"name\":\"E9 New Name\",\"description\":\"after\"}"))
                .andExpect(status().isCreated())
                // 1. The response echoes the id the caller sent.
                .andExpect(header().string("X-Request-Id", requestId));

        // 2. At least one log line of the request carries the same
        //    requestId/userId/bankId (the audit INFO line; the MDC pattern in
        //    logback-spring.xml prints exactly these keys).
        ILoggingEvent line = auditLogLines.list.stream()
                .filter(event -> requestId.equals(event.getMDCPropertyMap().get("requestId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line carried the request id"));
        assertThat(line.getMDCPropertyMap().get("userId"))
                .isEqualTo(String.valueOf(writer.getAppUserId()));
        assertThat(line.getMDCPropertyMap().get("bankId"))
                .isEqualTo(String.valueOf(writer.getBankId()));

        // 3. The audit row of the action carries the same three values, the
        //    actor's identity and the old/new snapshots (C7 + B1).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ActorUserId, ActorEmail, ActorRole, BankId, RequestId, EntityId, "
                        + "OldValues, NewValues, Outcome, SessionJti, Ip "
                        + "FROM AuditEvent WHERE Action = 'TEMPLATE_UPDATED' AND RequestId = ?",
                requestId);
        assertThat(row.get("ActorUserId")).isEqualTo(writer.getAppUserId());
        assertThat(row.get("ActorRole")).isEqualTo("READ_WRITE");
        assertThat(row.get("BankId")).isEqualTo(writer.getBankId());
        assertThat(row.get("EntityId")).isEqualTo(String.valueOf(template.getTemplateId()));
        assertThat(json(row.get("OldValues"))).contains("E9 Old Name");
        assertThat(json(row.get("NewValues"))).contains("E9 New Name");
        assertThat(row.get("Outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("SessionJti")).isNotNull(); // the access token's jti
    }

    @Test
    void authorizationDenialIsAuditedWithThePathAndTheSameRequestId() throws Exception {
        String requestId = "e9-denied-" + UUID.randomUUID();

        mvc.perform(put("/Templates/Update")
                        .header("Authorization", "Bearer " + jwtService.issueToken(readOnly))
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + template.getTemplateId()
                                + ",\"name\":\"x\",\"description\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Request-Id", requestId));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ActorUserId, BankId, EntityType, EntityId, Outcome "
                        + "FROM AuditEvent WHERE Action = 'AUTHORIZATION_DENIED' AND RequestId = ?",
                requestId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("ActorUserId")).isEqualTo(readOnly.getAppUserId());
        assertThat(rows.get(0).get("BankId")).isEqualTo(readOnly.getBankId());
        assertThat(rows.get(0).get("EntityType")).isEqualTo("Route");
        assertThat(rows.get(0).get("EntityId")).isEqualTo("/Templates/Update");
        assertThat(rows.get(0).get("Outcome")).isEqualTo("DENIED");

        // The template is untouched — the denial really denied.
        assertThat(templates.findById(template.getTemplateId()).orElseThrow().getName())
                .isEqualTo("E9 Old Name");
    }

    @Test
    void unsafeInboundRequestIdIsReplacedByAMintedUuid() throws Exception {
        // Legal as an HTTP header (control characters are already rejected by
        // Spring Security's StrictHttpFirewall) but outside the filter's safe
        // token shape: wrong characters AND longer than 64.
        String echoed = mvc.perform(put("/Templates/Update")
                        .header("Authorization", "Bearer " + jwtService.issueToken(writer))
                        .header("X-Request-Id", "evil=id;" + "x".repeat(70))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + template.getTemplateId()
                                + ",\"name\":\"E9 Old Name\",\"description\":\"before\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(echoed).isNotNull().doesNotContain("evil");
        assertThat(UUID.fromString(echoed).version()).isEqualTo(7);
    }

    /** H2 hands JSON columns back as bytes; PostgreSQL as text. */
    private static String json(Object columnValue) {
        if (columnValue instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return String.valueOf(columnValue);
    }

    private AppUser user(Role role) {
        AppUser entity = new AppUser();
        entity.setEmail("e9-" + role + "-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("E9 " + role);
        entity.setBankId(1);
        entity.setRole(role);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger(JpaAuditRecorder.class);
    }
}
