package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws28 (contracts §8): deprecated routes keep answering, carry
 * {@code Deprecation}/{@code Sunset}/{@code Link}, are {@code deprecated:
 * true} in the generated spec — and the set of deprecated operations equals
 * the table in docs/api/versioning-policy.md §7. The removed AI proxy and
 * transform routes are gone.
 */
class DeprecatedEndpointsTest extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path POLICY = Path.of("docs", "api", "versioning-policy.md");
    private static final Pattern TABLE_ROUTE =
            Pattern.compile("^\\| `(GET|POST|PUT|DELETE|PATCH) (/[^`]+)` \\| 1\\.2\\.0 \\|");

    @Test
    void aDeprecatedRouteAnswersNormallyWithTheHeaders() throws Exception {
        Bank bank = newBank("DEP");
        AppUser admin = newUser(bank, "dep-admin", Role.ADMIN, null);

        MvcResult deprecated = mvc.perform(get("/ProcessStep/GetAll")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(deprecated.getResponse().getHeader("Deprecation")).isEqualTo("true");
        assertThat(deprecated.getResponse().getHeader("Sunset"))
                .isEqualTo("Thu, 01 Apr 2027 00:00:00 GMT");
        assertThat(deprecated.getResponse().getHeader("Link"))
                .isEqualTo("</docs/api/versioning-policy.md>; rel=\"deprecation\"");

        // Method-level: only the deprecated method of a controller carries them.
        MvcResult current = mvc.perform(get("/ImportHistory/GetAll")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn();
        assertThat(current.getResponse().getHeader("Deprecation")).isNull();
        MvcResult byId = mvc.perform(get("/ImportHistory/GetById/999999")
                        .header("Authorization", bearer(admin)))
                .andReturn();
        assertThat(byId.getResponse().getHeader("Deprecation")).isEqualTo("true");
    }

    @Test
    void theSpecMarksExactlyTheDocumentedRoutesDeprecated() throws Exception {
        JsonNode spec = MAPPER.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Set<String> inSpec = new TreeSet<>();
        Iterator<Map.Entry<String, JsonNode>> paths = spec.get("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> path = paths.next();
            Iterator<Map.Entry<String, JsonNode>> operations = path.getValue().fields();
            while (operations.hasNext()) {
                Map.Entry<String, JsonNode> operation = operations.next();
                if (operation.getValue().path("deprecated").asBoolean(false)) {
                    inSpec.add(operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                    assertThat(operation.getValue().path("description").asText())
                            .contains("removal not before 2027-04-01");
                }
            }
        }

        Set<String> documented = new TreeSet<>();
        for (String line : Files.readAllLines(POLICY)) {
            Matcher route = TABLE_ROUTE.matcher(line);
            if (route.find()) {
                documented.add(route.group(1) + " " + route.group(2));
            }
        }
        assertThat(documented).hasSize(20);
        assertThat(inSpec).isEqualTo(documented);
        assertThat(spec.get("paths").has("/api/aifiles/send")).isFalse();
        assertThat(spec.get("paths").has("/api/transform/transform")).isFalse();
    }

    @Test
    void theRemovedAiProxyIsGone() throws Exception {
        Bank bank = newBank("DEP");
        AppUser writer = newUser(bank, "dep-writer", Role.READ_WRITE, null);
        int status = mvc.perform(post("/api/aifiles/send").header("Authorization", bearer(writer))
                        .contentType("application/json").content("{\"prompt\":\"hi\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(404, 405);
    }
}
