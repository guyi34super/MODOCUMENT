package com.modocument.doccreation.api;

import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.repo.DocBlueprintJpaRepository;
import com.modocument.doccreation.repo.DocBlueprintSectionJpaRepository;
import com.modocument.doccreation.repo.DocContentBlockJpaRepository;
import com.modocument.doccreation.repo.DocImageAssetJpaRepository;
import com.modocument.doccreation.repo.DocInstanceJpaRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The retired Word path after the PDF switch: Word-based templates (V300
 * Kind DOCX_LEGACY) are hidden from the lists and answer 410 GONE (as do
 * documents built on them); the bank administrator's audited purge deletes
 * them with their documents, content rows and stored files — in the
 * caller's bank only — and a second run finds nothing.
 */
class DocLegacyPurgeApiTest extends IsolatedStorageApiTestSupport {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private BankJpaRepository banks;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocBlueprintJpaRepository blueprints;
    @Autowired
    private DocBlueprintSectionJpaRepository sections;
    @Autowired
    private DocInstanceJpaRepository instances;
    @Autowired
    private DocImageAssetJpaRepository assets;
    @Autowired
    private DocContentBlockJpaRepository blocks;
    @Autowired
    private FakePdfTemplateEngine engine;

    private Bank bank;
    private Bank otherBank;
    private AppUser author;
    private AppUser admin;
    private AppUser otherAuthor;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("LGA" + tag.substring(0, 5).toUpperCase(), "Legacy A"));
        otherBank = banks.save(new Bank("LGB" + tag.substring(0, 5).toUpperCase(), "Legacy B"));
        author = user("legacy-author-" + tag, bank, Role.READ_WRITE);
        admin = user("legacy-admin-" + tag, bank, Role.ADMIN);
        otherAuthor = user("legacy-other-" + tag, otherBank, Role.READ_WRITE);
    }

    @AfterEach
    void cleanUp() {
        for (Bank b : List.of(bank, otherBank)) {
            int bankId = b.getBankId();
            jdbc.update("DELETE FROM DocContentBlock WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstanceImage WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintSection WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
            jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
        }
        userIds.forEach(users::deleteById);
        userIds.clear();
        banks.delete(bank);
        banks.delete(otherBank);
    }

    private AppUser user(String name, Bank owningBank, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(name + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(name);
        entity.setBankId(owningBank.getBankId());
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        userIds.add(saved.getAppUserId());
        return saved;
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + jwtService.issueToken(user));
    }

    /** A Word-path template with one document, its section, block, photo and export — and their files. */
    private record Legacy(int blueprintId, int instanceId, List<Path> files) {
    }

    private Legacy legacy(AppUser owner) throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Path uploads = Files.createDirectories(STORAGE_ROOT.resolve("uploads"));
        Path exports = Files.createDirectories(STORAGE_ROOT.resolve("exports"));
        Path docx = Files.writeString(uploads.resolve("legacy-" + tag + ".docx"), "docx");
        Path photo = Files.writeString(uploads.resolve("legacy-" + tag + ".png"), "png");

        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setOwnerUserId(owner.getAppUserId());
        blueprint.setBankId(owner.getBankId());
        blueprint.setName("Word report " + tag);
        blueprint.setFileName(docx.getFileName().toString());
        blueprint.setFlowKey("DEFAULT");
        blueprint.setCreatedDate(LocalDateTime.now());
        blueprint = blueprints.save(blueprint);
        assertThat(blueprint.getKind()).as("rows of the Word path default to DOCX_LEGACY")
                .isEqualTo(DocBlueprint.KIND_DOCX_LEGACY);

        DocBlueprintSection section = new DocBlueprintSection();
        section.setBlueprintId(blueprint.getDocBlueprintId());
        section.setBankId(owner.getBankId());
        section.setTitle("Overview");
        section.setHeadingLevel(1);
        section = sections.save(section);

        DocInstance instance = new DocInstance();
        instance.setOwnerUserId(owner.getAppUserId());
        instance.setBankId(owner.getBankId());
        instance.setBlueprintId(blueprint.getDocBlueprintId());
        instance.setTitle("Word document " + tag);
        instance.setStatus(DocInstance.STATUS_GENERATED);
        instance = instances.save(instance);
        Path export = Files.writeString(exports.resolve("doccreation-instance" + instance.getDocInstanceId()
                + ".docx"), "docx out");
        instance.setLastGeneratedDocx(export.getFileName().toString());
        instance = instances.save(instance);

        DocImageAsset asset = new DocImageAsset();
        asset.setInstanceId(instance.getDocInstanceId());
        asset.setBankId(owner.getBankId());
        asset.setFileName(photo.getFileName().toString());
        asset.setOriginalFileName("roof.png");
        asset.setContentType("image/png");
        asset.setAssignedSectionId(section.getDocBlueprintSectionId());
        asset = assets.save(asset);

        DocContentBlock block = new DocContentBlock();
        block.setInstanceId(instance.getDocInstanceId());
        block.setBankId(owner.getBankId());
        block.setSectionId(section.getDocBlueprintSectionId());
        block.setBlockType(DocContentBlock.TYPE_IMAGE);
        block.setAssetId(asset.getDocImageAssetId());
        blocks.save(block);
        return new Legacy(blueprint.getDocBlueprintId(), instance.getDocInstanceId(), List.of(docx, photo, export));
    }

    @Test
    void legacyTemplatesAreHiddenAndGone() throws Exception {
        Legacy old = legacy(author);

        mvc.perform(as(get("/api/doccreation/blueprints"), author))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/doccreation/instances"), author))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/doccreation/blueprints/" + old.blueprintId()), author))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LEGACY_TEMPLATE"));
        mvc.perform(as(get("/api/doccreation/blueprints/" + old.blueprintId() + "/file"), author))
                .andExpect(status().isGone());
        mvc.perform(as(get("/api/doccreation/instances/" + old.instanceId()), author))
                .andExpect(status().isGone());
        mvc.perform(as(put("/api/doccreation/instances/" + old.instanceId() + "/values"), author)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{}}"))
                .andExpect(status().isGone());
        mvc.perform(as(post("/api/doccreation/instances"), author)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + old.blueprintId() + ",\"title\":\"x\"}"))
                .andExpect(status().isGone());
        // Another user still cannot tell it exists.
        mvc.perform(as(get("/api/doccreation/blueprints/" + old.blueprintId()), otherAuthor))
                .andExpect(status().isNotFound());
        // The removed Word routes are gone.
        mvc.perform(as(get("/api/doccreation/blueprints/" + old.blueprintId() + "/paragraphs"), author))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePurgeDeletesTheCallersBankLegacyOnlyWithFilesAndAudit() throws Exception {
        Legacy mine = legacy(author);
        Legacy theirs = legacy(otherAuthor);
        // A PDF template of the same bank survives the purge.
        int pdfTemplate = jsonId(mvc.perform(as(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "f.pdf", "application/pdf",
                                FakePdfTemplateEngine.blankPdf(1, 0))), author))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        mvc.perform(as(post("/api/admin/doccreation/purge-legacy"), author))
                .andExpect(status().isForbidden());

        mvc.perform(as(post("/api/admin/doccreation/purge-legacy"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blueprints").value(1))
                .andExpect(jsonPath("$.instances").value(1))
                .andExpect(jsonPath("$.files").value(3));

        assertThat(blueprints.findById(mine.blueprintId())).isEmpty();
        assertThat(instances.findById(mine.instanceId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocBlueprintSection WHERE BlueprintId = ?",
                Integer.class, mine.blueprintId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocContentBlock WHERE InstanceId = ?",
                Integer.class, mine.instanceId())).isZero();
        assertThat(mine.files()).allSatisfy(file -> assertThat(file).doesNotExist());
        String evidence = jdbc.queryForObject("SELECT NewValues FROM AuditEvent WHERE Action = 'DOC_LEGACY_PURGED' "
                + "AND EntityId = ?", String.class, String.valueOf(bank.getBankId()));
        assertThat(evidence).contains("\"blueprints\":1").contains("\"instances\":1").contains("\"files\":3");

        // The other bank's Word template is untouched; the PDF one too.
        assertThat(blueprints.findById(theirs.blueprintId())).isPresent();
        assertThat(theirs.files()).allSatisfy(file -> assertThat(file).exists());
        assertThat(blueprints.findById(pdfTemplate)).isPresent();

        // Idempotent.
        mvc.perform(as(post("/api/admin/doccreation/purge-legacy"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blueprints").value(0))
                .andExpect(jsonPath("$.files").value(0));
        mvc.perform(as(delete("/api/doccreation/blueprints/" + pdfTemplate), author))
                .andExpect(status().isNoContent());
    }

    private static int jsonId(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("id").asInt();
    }
}
