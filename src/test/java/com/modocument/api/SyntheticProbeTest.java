package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SUP-S11 share (ws18): the public synthetic probe answers without a token
 * with the build version and a real database round trip — and nothing else
 * — is rate-limited per client ip, and is GET-only.
 */
class SyntheticProbeTest extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String randomIp() {
        return "10.99." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }

    @Test
    void answersAnonymouslyWithVersionAndDatabaseStatusOnly() throws Exception {
        MvcResult result = mvc.perform(get("/api/probe/synthetic").header("X-Forwarded-For", randomIp()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        body.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("status", "version", "db", "checkedAt");
        assertThat(body.get("status").asText()).isEqualTo("UP");
        assertThat(body.get("db").asBoolean()).isTrue();
        assertThat(body.get("version").asText()).isNotBlank();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void isRateLimitedPerClientIp() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 60; i++) {
            mvc.perform(get("/api/probe/synthetic").header("X-Forwarded-For", ip))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/probe/synthetic").header("X-Forwarded-For", ip))
                .andExpect(status().isTooManyRequests());
        // Another address is unaffected.
        mvc.perform(get("/api/probe/synthetic").header("X-Forwarded-For", randomIp()))
                .andExpect(status().isOk());
    }

    @Test
    void onlyGetIsPublic() throws Exception {
        mvc.perform(post("/api/probe/synthetic").header("X-Forwarded-For", randomIp()))
                .andExpect(status().isUnauthorized());
    }
}
