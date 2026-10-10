package com.modocument.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.release.ReleaseNotesParser;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws24 system routes over the real security chain: the contract shapes of
 * {@code GET /api/system/version} and {@code GET /api/system/release-notes},
 * SYSTEM_INFO for every bank role, 401 for anonymous callers, and the
 * release-notes limit bounds. Release notes come from the CHANGELOG.md that
 * Maven packaged as classpath:release/CHANGELOG.md, so the newest release
 * served must be the pom version ({@code scripts/check-version-sync.sh}
 * keeps the two equal).
 *
 * <p>{@code @Transactional}: the per-role users roll back (same context
 * configuration as PermissionEnforcementTest, so the cached context is
 * shared).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SystemInfoControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ObjectProvider<BuildProperties> buildProperties;
    @Autowired
    private ObjectProvider<GitProperties> gitProperties;

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get("/api/system/version")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/system/release-notes")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyBankRoleMayReadBothRoutes() throws Exception {
        for (Role role : new Role[] {Role.READ_ONLY, Role.READ_WRITE, Role.APPROVER, Role.ADMIN}) {
            String auth = bearer(role);
            mvc.perform(get("/api/system/version").header("Authorization", auth))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/system/release-notes").header("Authorization", auth))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void versionHasTheContractShape() throws Exception {
        JsonNode body = getJson("/api/system/version", bearer(Role.READ_ONLY));

        assertThat(fieldNames(body)).containsExactly(
                "version", "commit", "buildTime", "schemaVersion", "deployedAt", "environment");
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null) { // present whenever Maven built the module (build-info goal)
            assertThat(body.get("version").asText()).isEqualTo(build.getVersion());
            assertThat(body.get("buildTime").asText()).isEqualTo(build.getTime().toString());
        }
        GitProperties git = gitProperties.getIfAvailable();
        if (git != null) { // absent in a build without .git — then null
            assertThat(body.get("commit").asText()).isEqualTo(git.getShortCommitId());
        } else {
            assertThat(body.get("commit").isNull()).isTrue();
        }
        // H2 test profile: Flyway disabled → no schema version; no recorder yet.
        assertThat(body.get("schemaVersion").isNull()).isTrue();
        assertThat(body.get("deployedAt").isNull()).isTrue();
        assertThat(body.get("environment").asText()).isEqualTo("local");
    }

    @Test
    void releaseNotesHaveTheContractShapeNewestFirst() throws Exception {
        JsonNode body = getJson("/api/system/release-notes", bearer(Role.READ_ONLY));

        assertThat(fieldNames(body)).containsExactly("releases");
        JsonNode releases = body.get("releases");
        assertThat(releases.isArray()).isTrue();
        assertThat(releases.size()).isBetween(1, 5);

        JsonNode newest = releases.get(0);
        assertThat(fieldNames(newest)).containsExactly("version", "date", "sections");
        assertThat(newest.get("version").asText())
                .as("newest released CHANGELOG heading = project version")
                .isEqualTo(projectVersion());
        assertThat(newest.get("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(fieldNames(newest.get("sections"))).isNotEmpty()
                .isSubsetOf(ReleaseNotesParser.SECTIONS);
        newest.get("sections").forEach(entries -> {
            assertThat(entries.isArray()).isTrue();
            assertThat(entries.size()).isPositive();
            entries.forEach(entry -> assertThat(entry.asText()).isNotBlank().doesNotContain("`"));
        });
        // [Unreleased] is never served.
        releases.forEach(release -> assertThat(release.get("version").asText()).matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    void releaseNotesLimitIsBoundedOneToTwenty() throws Exception {
        String auth = bearer(Role.READ_ONLY);
        assertThat(getJson("/api/system/release-notes?limit=1", auth).get("releases").size()).isEqualTo(1);
        mvc.perform(get("/api/system/release-notes?limit=20").header("Authorization", auth))
                .andExpect(status().isOk());
        for (String bad : new String[] {"0", "21", "-1", "abc"}) {
            mvc.perform(get("/api/system/release-notes?limit=" + bad).header("Authorization", auth))
                    .andExpect(status().isBadRequest());
        }
    }

    private JsonNode getJson(String path, String auth) throws Exception {
        String json = mvc.perform(get(path).header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(json);
    }

    private String bearer(Role role) {
        AppUser user = new AppUser();
        user.setEmail("sysinfo-" + role + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setDisplayName("System info " + role);
        user.setBankId(1);
        user.setRole(role);
        user.setSettingsJson("{}");
        return "Bearer " + jwtService.issueToken(users.save(user));
    }

    private String projectVersion() throws Exception {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null) {
            return build.getVersion();
        }
        String pom = Files.readString(Path.of("pom.xml"));
        String afterParent = pom.substring(pom.indexOf("</parent>"));
        int start = afterParent.indexOf("<version>") + "<version>".length();
        return afterParent.substring(start, afterParent.indexOf("</version>", start)).strip();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
