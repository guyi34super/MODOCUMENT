package com.modocument.api;

import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The documented config-off mode (ws10):
 * {@code modocument.security.maker-checker.template-changes=false} — which
 * the shared H2 test profile sets, see application-test.yml — lets a
 * single-user setup apply template update/delete DIRECTLY, with the
 * original response shapes (201 on update, 204 on delete) and no
 * PendingChange row. The gated flow is MakerCheckerIntegrationTest's
 * subject; user-security changes have no such switch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateMakerCheckerConfigOffIntegrationTest {

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
    private Template template;

    @BeforeEach
    void seedWriterAndTemplate() {
        AppUser entity = new AppUser();
        entity.setEmail("cfgoff-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("Config Off");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        writer = users.save(entity);

        Template seeded = new Template(0, "CfgOff Before " + UUID.randomUUID(), "before");
        seeded.setOwnerUserId(writer.getAppUserId());
        seeded.setBankId(writer.getBankId());
        template = templates.save(seeded);
    }

    @AfterEach
    void cleanUp() {
        templates.findById(template.getTemplateId()).ifPresent(templates::delete);
        users.deleteById(writer.getAppUserId());
    }

    private String bearer() {
        return "Bearer " + jwtService.issueToken(writer);
    }

    @Test
    void templateUpdateAndDeleteApplyDirectlyWhenTheGateIsOff() throws Exception {
        // Update: the pre-ws10 response shape (201) and an immediate effect.
        mvc.perform(put("/Templates/Update")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":" + template.getTemplateId()
                                + ",\"name\":\"CfgOff After\",\"description\":\"after\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("CfgOff After"));
        assertThat(templates.findById(template.getTemplateId()).orElseThrow().getName())
                .isEqualTo("CfgOff After");

        // Delete: 204 and gone.
        mvc.perform(delete("/Templates/" + template.getTemplateId())
                        .header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        assertThat(templates.findById(template.getTemplateId())).isEmpty();

        // No approval queue involvement anywhere on this path.
        Integer pendingRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM PendingChange WHERE MakerUserId = ?",
                Integer.class, writer.getAppUserId());
        assertThat(pendingRows).isZero();
    }
}
