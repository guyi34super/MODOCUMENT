package com.modocument.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bank-shared templates under the maker/checker template gate (ws22, gate
 * ON — same properties as MakerCheckerIntegrationTest, so the Spring
 * context is shared). The write rule is judged at SUBMIT for the maker and
 * again at APPLY for the maker (never the checker):
 * <ul>
 *   <li>a colleague without template:publish cannot even submit a change of
 *       a shared template (403), nor of a private one (404);</li>
 *   <li>another admin of the bank submits; a different admin approves; the
 *       change applies;</li>
 *   <li>a maker who lost template:publish between submit and approval: the
 *       approval fails and the change is FAILED.</li>
 * </ul>
 */
@SpringBootTest(properties = "modocument.security.maker-checker.template-changes=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateVisibilityMakerCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private IngestionJobJpaRepository ingestionJobs;
    @Autowired
    private JdbcTemplate jdbc;

    private String tag;
    private Bank bank;
    private AppUser owner;
    private AppUser coAdmin;
    private AppUser writer;
    private Template priv;
    private Template shared;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("TVM" + tag.substring(0, 4).toUpperCase(), "Visibility MC"));
        owner = user("owner", Role.ADMIN);
        coAdmin = user("coadmin", Role.ADMIN);
        writer = user("writer", Role.READ_WRITE);
        priv = template("Private", TemplateVisibility.PRIVATE);
        shared = template("Shared", TemplateVisibility.BANK);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM PendingChange WHERE BankId = ?", bank.getBankId());
        jdbc.update("DELETE FROM IngestionJob WHERE BankId = ?", bank.getBankId());
        jdbc.update("DELETE FROM Template WHERE BankId = ?", bank.getBankId());
        userIds.forEach(users::deleteById);
        banks.delete(bank);
    }

    @Test
    void aColleagueWithoutTemplatePublishCannotSubmitChangesToASharedTemplate()
            throws Exception {
        mvc.perform(put("/Templates/Update").header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(shared, "Renamed")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/Templates/" + shared.getTemplateId())
                        .header("Authorization", bearer(writer)))
                .andExpect(status().isForbidden());
        // Someone else's PRIVATE template does not exist for them.
        mvc.perform(put("/Templates/Update").header("Authorization", bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(priv, "Renamed")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE BankId = ?",
                Integer.class, bank.getBankId())).isZero();
    }

    @Test
    void anotherAdminChangesASharedTemplateThroughApproval() throws Exception {
        int changeId = submitUpdate(coAdmin, shared, "Renamed " + tag);
        assertThat(templates.findById(shared.getTemplateId()).orElseThrow().getName())
                .isNotEqualTo("Renamed " + tag);

        mvc.perform(post("/api/admin/changes/" + changeId + "/approve")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());

        assertThat(templates.findById(shared.getTemplateId()).orElseThrow().getName())
                .isEqualTo("Renamed " + tag);
        assertThat(statusOf(changeId)).isEqualTo("APPLIED");
    }

    @Test
    void aMakerWhoLostTemplatePublishBeforeApprovalCannotApply() throws Exception {
        int changeId = submitUpdate(coAdmin, shared, "Renamed " + tag);
        coAdmin.setRole(Role.READ_WRITE);
        users.save(coAdmin);

        mvc.perform(post("/api/admin/changes/" + changeId + "/approve")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden());

        assertThat(statusOf(changeId)).isEqualTo("FAILED");
        assertThat(templates.findById(shared.getTemplateId()).orElseThrow().getName())
                .isNotEqualTo("Renamed " + tag);
    }

    @Test
    void deletingASharedTemplateInUseByColleaguesIsRefusedAtSubmit() throws Exception {
        IngestionJob running = new IngestionJob();
        running.setBankId(bank.getBankId());
        running.setOwnerUserId(writer.getAppUserId());
        running.setTemplateId(shared.getTemplateId());
        running.setFileName("in-use-" + tag + ".csv");
        running.setStatus(JobStatus.RUNNING);
        running.setCreatedDate(LocalDateTime.now());
        ingestionJobs.save(running);

        // The maker hears it immediately: 409, and nothing is queued.
        mvc.perform(delete("/Templates/" + shared.getTemplateId())
                        .header("Authorization", bearer(coAdmin)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PendingChange WHERE BankId = ?",
                Integer.class, bank.getBankId())).isZero();
    }

    // ----------------------------------------------------------- helpers

    private int submitUpdate(AppUser maker, Template t, String name) throws Exception {
        MvcResult result = mvc.perform(put("/Templates/Update")
                        .header("Authorization", bearer(maker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(t, name)))
                .andExpect(status().isAccepted())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .get("pendingChangeId").asInt();
    }

    private static String updateBody(Template t, String name) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.of(
                "templateId", t.getTemplateId(), "name", name, "description", "d"));
    }

    private String statusOf(int changeId) {
        return jdbc.queryForObject("SELECT Status FROM PendingChange WHERE PendingChangeId = ?",
                String.class, changeId);
    }

    private AppUser user(String prefix, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(prefix + "-mc-" + tag + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName(prefix + " " + tag);
        entity.setBankId(bank.getBankId());
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        userIds.add(saved.getAppUserId());
        return saved;
    }

    private Template template(String name, TemplateVisibility visibility) {
        Template entity = new Template(0, name + " " + tag, "d");
        entity.setOwnerUserId(owner.getAppUserId());
        entity.setBankId(bank.getBankId());
        entity.setVisibility(visibility);
        return templates.save(entity);
    }

    private String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }
}
