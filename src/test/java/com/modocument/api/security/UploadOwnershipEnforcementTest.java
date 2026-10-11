package com.modocument.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.UploadedFileJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * File-ownership enforcement (V20): an upload records its owner, and every
 * endpoint that accepts a stored file name (JSON ingestion-job creation,
 * transform) answers 404 for a file the caller does not own — including
 * stored names that predate the ownership table (clean cutover) — while the
 * owner sails through.
 */
@Transactional
class UploadOwnershipEnforcementTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private UploadedFileJpaRepository uploadedFiles;
    @Autowired
    private JwtService jwtService;

    private AppUser alice;
    private AppUser mallory;
    private String aliceAuth;
    private String malloryAuth;

    @BeforeEach
    void twoReadWriteUsers() {
        alice = user("alice");
        mallory = user("mallory");
        aliceAuth = "Bearer " + jwtService.issueToken(alice);
        malloryAuth = "Bearer " + jwtService.issueToken(mallory);
    }

    private AppUser user(String name) {
        AppUser entity = new AppUser();
        entity.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName(name);
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        return users.save(entity);
    }

    private int templateOwnedBy(AppUser owner) {
        Template template = new Template(0, "T-" + UUID.randomUUID(), "d");
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(owner.getBankId());
        return templates.save(template).getTemplateId();
    }

    private String upload(String auth) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/fileupload/upload")
                        .file(new MockMultipartFile("file", "owned.csv", "text/csv",
                                "FirstName\nAda\n".getBytes()))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString())
                .get("fileName").asText();
    }

    @Test
    void uploadRecordsAnOwnershipRow() throws Exception {
        String storedName = upload(aliceAuth);

        var row = uploadedFiles.findByStoredNameAndOwnerUserId(storedName, alice.getAppUserId());
        assertThat(row).isPresent();
        assertThat(row.get().getOriginalName()).isEqualTo("owned.csv");
        assertThat(row.get().getBankId()).isEqualTo(alice.getBankId());
        assertThat(row.get().getSizeBytes()).isGreaterThan(0);
    }

    @Test
    void ingestionJsonCreateAcceptsTheOwnerAndRejectsEveryoneElse() throws Exception {
        String storedName = upload(aliceAuth);

        // Another authenticated READ_WRITE user who knows the stored name:
        // 404, indistinguishable from "no such file".
        mvc.perform(post("/api/ingestion/jobs").header("Authorization", malloryAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"" + storedName + "\",\"templateId\":"
                                + templateOwnedBy(mallory) + ",\"autoRun\":false}"))
                .andExpect(status().isNotFound());

        // The owner proceeds (201: job created, not auto-run).
        mvc.perform(post("/api/ingestion/jobs").header("Authorization", aliceAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"" + storedName + "\",\"templateId\":"
                                + templateOwnedBy(alice) + ",\"autoRun\":false}"))
                .andExpect(status().isCreated());
    }

    @Test
    void preV20FilesOnDiskWithoutARowAreNotFound() throws Exception {
        // A file placed in uploads/ before the ownership table existed: no
        // row, so no one may reference it (clean cutover, see V20 header).
        Path uploads = STORAGE_ROOT.resolve("uploads");
        Files.createDirectories(uploads);
        Files.writeString(uploads.resolve("legacy.csv"), "a,b\n");

        mvc.perform(post("/api/ingestion/jobs").header("Authorization", aliceAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"legacy.csv\",\"templateId\":"
                                + templateOwnedBy(alice) + ",\"autoRun\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRemovedTransformRouteNoLongerExists() throws Exception {
        // ws28: POST /api/transform/transform was removed (unused legacy AI
        // path); the ownership rule above covers every remaining file route.
        String storedName = upload(aliceAuth);
        int status = mvc.perform(post("/api/transform/transform").header("Authorization", malloryAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"" + storedName + "\"}"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isIn(404, 405);
    }
}
