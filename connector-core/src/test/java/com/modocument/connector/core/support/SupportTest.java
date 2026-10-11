package com.modocument.connector.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.connector.core.spi.ConnectionType;
import com.modocument.connector.core.spi.OutboundBatch;
import com.modocument.connector.core.webhook.WebhookSigner;
import com.modocument.connector.core.wire.AgentSftpFile;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared helpers: specs, safe error messages, write chunks, the SFTP wire record, webhook signing. */
class SupportTest {

    @Test
    void relativePathsRefuseEveryEscape() {
        assertThat(Specs.relativePath("/a/b", "path", true)).isEqualTo("a/b");
        for (String bad : List.of("../etc", "/etc", "a/../../b", "a\\b", "./x", "a//b", "http://x/y", "a?b")) {
            assertThatThrownBy(() -> Specs.relativePath(bad, "folder", false))
                    .as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a plain path");
        }
        assertThatThrownBy(() -> Specs.allowOnly(Map.of("sql", "drop"), "source spec", java.util.Set.of("table")))
                .hasMessageContaining("Unknown source spec key 'sql'");
    }

    @Test
    void errorMessagesAreClassifiedAndCredentialFree() {
        assertThat(ConnectorErrors.safeMessage(new RuntimeException(new UnknownHostException("db"))))
                .isEqualTo("The host could not be resolved.");
        String message = ConnectorErrors.safeMessage(new SQLException(
                "login failed: password=hunter2 token: abc.def Authorization Bearer eyJhbGciOiJIUzI1NiJ9.x.y",
                "28P01"));
        assertThat(message).contains("28P01").doesNotContain("hunter2").doesNotContain("abc.def")
                .doesNotContain("eyJhbGci");
        assertThat(ConnectorErrors.safeMessage(new IllegalStateException("x".repeat(500)))).hasSize(303);
        assertThat(ConnectorErrors.safeMessage(new IllegalArgumentException("mail a@b.example"),
                text -> text.replace("a@b.example", "***"))).isEqualTo("mail ***");
    }

    @Test
    void writeChunksCarryDerivedIdempotencyKeys() {
        List<Map<String, Object>> records = List.of(Map.of("i", 1), Map.of("i", 2), Map.of("i", 3));
        OutboundBatch batch = new OutboundBatch("key", "VALIDATED_RECORDS", "JSON", records, null, null, null);
        assertThat(batch.chunks(2)).extracting(OutboundBatch::idempotencyKey).containsExactly("key-0", "key-1");
        assertThat(batch.chunks(2)).extracting(b -> b.records().size()).containsExactly(2, 1);
        assertThat(batch.chunks(3)).containsExactly(batch);
        OutboundBatch file = new OutboundBatch("f", "RELEASED_DOCUMENTS", "PDF", records, "a.pdf", "application/pdf",
                new byte[] {1});
        assertThat(file.chunks(1)).containsExactly(file);
        assertThat(OutboundBatch.batchSize(ConnectionType.REST_API, Map.of())).isEqualTo(100);
        assertThat(OutboundBatch.batchSize(ConnectionType.DATABASE, Map.of())).isEqualTo(500);
        assertThat(OutboundBatch.batchSize(ConnectionType.SFTP, Map.of())).isEqualTo(Integer.MAX_VALUE);
        assertThat(OutboundBatch.batchSize(ConnectionType.REST_API, Map.of("batchSize", "7"))).isEqualTo(7);
        assertThat(OutboundBatch.batchSize(ConnectionType.REST_API, Map.of("batchSize", "x"))).isEqualTo(100);
    }

    @Test
    void theSftpWireRecordRoundTrips() throws Exception {
        byte[] content = "id\n1\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> record = AgentSftpFile.of("a.csv", "inbox/a.csv", content).toRecord();
        String json = new ObjectMapper().writeValueAsString(record);
        assertThat(json).isEqualTo("{\"fileName\":\"a.csv\",\"remotePath\":\"inbox/a.csv\",\"size\":5,"
                + "\"contentBase64\":\"aWQKMQo=\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        AgentSftpFile file = AgentSftpFile.fromRecord(parsed, 1024);
        assertThat(file.decode(1024)).isEqualTo(content);
        assertThat(file.remotePath()).isEqualTo("inbox/a.csv");
        assertThatThrownBy(() -> file.decode(2)).hasMessageContaining("cap");
    }

    @Test
    void webhookSignaturesVerifyWithinTheReplayWindowOnly() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-10-11T10:00:00Z");
        String signature = WebhookSigner.signature("secret-of-16-chars", now.getEpochSecond(), body);
        assertThat(WebhookSigner.verify("secret-of-16-chars", String.valueOf(now.getEpochSecond()), signature,
                body, now.plusSeconds(299))).isTrue();
        assertThat(WebhookSigner.verify("secret-of-16-chars", String.valueOf(now.getEpochSecond()), signature,
                body, now.plusSeconds(301))).isFalse();
    }
}
