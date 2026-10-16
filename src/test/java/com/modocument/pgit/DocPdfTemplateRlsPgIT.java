package com.modocument.pgit;

import com.modocument.application.port.TenantContext;
import com.modocument.doccreation.entity.DocInstanceImage;
import com.modocument.doccreation.entity.DocTemplateEdit;
import com.modocument.doccreation.entity.DocTemplateRegion;
import com.modocument.doccreation.repo.DocInstanceImageRepository;
import com.modocument.doccreation.repo.DocTemplateEditRepository;
import com.modocument.doccreation.repo.DocTemplateRegionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws31 (V301/V302) on the REAL schema with row level security ENFORCED: a
 * PDF template's regions and edits and a document's photos are visible and
 * writable in their own bank only. Bank B reads nothing of bank A's rows —
 * even when its repository call names bank A — and the policies' WITH CHECK
 * refuses bank B writing rows stamped with bank A.
 */
class DocPdfTemplateRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 896;
    private static final int BANK_B = 897;
    private static final int BLUEPRINT_A = 8960;
    private static final int INSTANCE_A = 8961;
    private static final int ASSET_A = 8962;

    @Autowired
    private DocTemplateRegionRepository regions;
    @Autowired
    private DocTemplateEditRepository edits;
    @Autowired
    private DocInstanceImageRepository images;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private FakePdfTemplateEngine engine;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void seedAsOwner() throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement()) {
            sql.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(896, 'PDF_A', 'PDF templates A'), (897, 'PDF_B', 'PDF templates B') ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO AppUsers (AppUserId, Email, PasswordHash, DisplayName, SettingsJson, "
                    + "CreatedDate, UpdatedDate, BankId, Role, TokenVersion) VALUES "
                    + "(8960, 'pdf-a@example.com', '{noop}x', 'PDF A', '{}', now(), now(), 896, 'READ_WRITE', 0) "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("DELETE FROM DocInstanceImage WHERE BankId IN (896, 897)");
            sql.execute("DELETE FROM DocTemplateRegion WHERE BankId IN (896, 897)");
            sql.execute("DELETE FROM DocTemplateEdit WHERE BankId IN (896, 897)");
            sql.execute("INSERT INTO DocBlueprint (DocBlueprintId, OwnerUserId, Name, FileName, FlowKey, "
                    + "CreatedDate, UpdatedDate, BankId, Kind, CurrentVersion, PageCount, UploadFlow) VALUES "
                    + "(8960, 8960, 'PDF A', 'doccreation/b896/blueprint8960/original.pdf', 'DEFAULT', now(), "
                    + "now(), 896, 'PDF_OVERLAY', 1, 1, 'BLANK') ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO DocInstance (DocInstanceId, OwnerUserId, BlueprintId, Title, Status, BankId, "
                    + "ValuesJson) VALUES (8961, 8960, 8960, 'PDF A doc', 'DRAFT', 896, '{\"name\":\"Jane\"}') "
                    + "ON CONFLICT DO NOTHING");
            sql.execute("INSERT INTO DocImageAsset (DocImageAssetId, InstanceId, FileName, OriginalFileName, "
                    + "ContentType, BankId) VALUES (8962, 8961, 'a.png', 'a.png', 'image/png', 896) "
                    + "ON CONFLICT DO NOTHING");
        }
    }

    private static DocTemplateRegion region(int bankId, String key) {
        DocTemplateRegion region = new DocTemplateRegion();
        region.setBankId(bankId);
        region.setBlueprintId(BLUEPRINT_A);
        region.setVersion(1);
        region.setRegionKey(key);
        region.setLabel(key);
        region.setKind("TEXT");
        region.setW(100);
        region.setH(20);
        region.setSource("MANUAL");
        return region;
    }

    private static DocTemplateEdit edit(int bankId, String id) {
        DocTemplateEdit edit = new DocTemplateEdit();
        edit.setBankId(bankId);
        edit.setBlueprintId(BLUEPRINT_A);
        edit.setVersion(1);
        edit.setEditId(id);
        edit.setKind("REMOVE");
        edit.setW(10);
        edit.setH(10);
        edit.setSource("USER");
        return edit;
    }

    private static DocInstanceImage image(int bankId) {
        DocInstanceImage image = new DocInstanceImage();
        image.setBankId(bankId);
        image.setInstanceId(INSTANCE_A);
        image.setAssetId(ASSET_A);
        image.setRegionKey("photo");
        image.setOriginalName("a.png");
        return image;
    }

    @Test
    void regionsEditsAndPhotosStayInTheirBank() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status -> {
            regions.saveAll(List.of(region(BANK_A, "name")));
            edits.saveAll(List.of(edit(BANK_A, "e1")));
            images.save(image(BANK_A));
        }));
        assertThat(count("SELECT count(*) FROM DocTemplateRegion WHERE BankId = 896")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocTemplateEdit WHERE BankId = 896")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocInstanceImage WHERE BankId = 896")).isEqualTo(1);

        // Own bank reads its rows.
        List<DocTemplateRegion> ownRegions = TenantContext.runAs(BANK_A, () -> transaction.execute(status ->
                regions.listByBlueprintVersion(BANK_A, BLUEPRINT_A, 1)));
        List<DocInstanceImage> ownImages = TenantContext.runAs(BANK_A, () -> transaction.execute(status ->
                images.listByInstance(BANK_A, INSTANCE_A)));
        assertThat(ownRegions).hasSize(1);
        assertThat(ownImages).hasSize(1);

        // Bank B sees nothing, even naming bank A.
        List<DocTemplateRegion> foreignRegions = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                regions.listByBlueprintVersion(BANK_A, BLUEPRINT_A, 1)));
        java.util.Map<Integer, List<DocTemplateRegion>> foreignByBlueprint = TenantContext.runAs(BANK_B, () ->
                transaction.execute(status -> regions.listByBlueprints(BANK_A, List.of(BLUEPRINT_A))));
        List<DocTemplateEdit> foreignEdits = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                edits.listByBlueprintVersion(BANK_A, BLUEPRINT_A, 1)));
        List<DocInstanceImage> foreignImages = TenantContext.runAs(BANK_B, () -> transaction.execute(status ->
                images.listByInstance(BANK_A, INSTANCE_A)));
        assertThat(foreignRegions).isEmpty();
        assertThat(foreignByBlueprint).isEmpty();
        assertThat(foreignEdits).isEmpty();
        assertThat(foreignImages).isEmpty();

        // Bank B's deletes naming bank A touch nothing.
        TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status -> {
            regions.deleteByBlueprint(BANK_A, BLUEPRINT_A);
            edits.deleteByBlueprint(BANK_A, BLUEPRINT_A);
            images.deleteByInstance(BANK_A, INSTANCE_A);
        }));
        assertThat(count("SELECT count(*) FROM DocTemplateRegion WHERE BankId = 896")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocTemplateEdit WHERE BankId = 896")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM DocInstanceImage WHERE BankId = 896")).isEqualTo(1);

        // WITH CHECK: bank B cannot write rows stamped with bank A.
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                regions.saveAll(List.of(region(BANK_A, "forged"))))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                edits.saveAll(List.of(edit(BANK_A, "forged"))))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> TenantContext.runAs(BANK_B, () -> transaction.executeWithoutResult(status ->
                images.save(image(BANK_A)))))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM DocTemplateRegion WHERE RegionKey = 'forged'")).isZero();
        assertThat(count("SELECT count(*) FROM DocTemplateEdit WHERE EditId = 'forged'")).isZero();
        assertThat(count("SELECT count(*) FROM DocInstanceImage WHERE BankId = 896")).isEqualTo(1);

        // Own bank's delete works.
        TenantContext.runAs(BANK_A, () -> transaction.executeWithoutResult(status -> {
            regions.deleteByBlueprintVersion(BANK_A, BLUEPRINT_A, 1);
            edits.deleteByBlueprintVersion(BANK_A, BLUEPRINT_A, 1);
            images.deleteByInstance(BANK_A, INSTANCE_A);
        }));
        assertThat(count("SELECT count(*) FROM DocTemplateRegion WHERE BankId = 896")).isZero();
        assertThat(count("SELECT count(*) FROM DocInstanceImage WHERE BankId = 896")).isZero();
    }

    /**
     * The whole PDF-template journey over HTTP on the real schema under RLS:
     * upload, confirm, a document with merged values and a photo, generate,
     * download, and the cleanup (bulk deletes included).
     */
    @Test
    void theTemplateAndDocumentJourneyRunsUnderRowLevelSecurity() throws Exception {
        AppUser principal = new AppUser();
        principal.setAppUserId(8960);
        principal.setBankId(BANK_A);
        principal.setRole(Role.READ_WRITE);
        principal.setTokenVersion(0);
        String token = "Bearer " + jwtService.issueToken(principal);
        engine.reset();

        MvcResult uploaded = mvc.perform(multipart("/api/doccreation/blueprints")
                        .file(new MockMultipartFile("file", "pg.pdf", "application/pdf",
                                FakePdfTemplateEngine.blankPdf(1, 0)))
                        .param("flow", "WITH_DATA")
                        .header("Authorization", token))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode draft = JSON.readTree(uploaded.getResponse().getContentAsString());
        int blueprintId = draft.get("id").asInt();
        ObjectNode overlay = JSON.createObjectNode();
        overlay.put("baseVersion", 1);
        overlay.set("regions", draft.get("regions"));
        overlay.set("edits", draft.get("edits"));
        overlay.put("confirm", true);
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/overlay").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(overlay.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        // A second save is version 2 (the bulk delete + re-insert path of a draft ran above).
        overlay.put("baseVersion", 1);
        mvc.perform(put("/api/doccreation/blueprints/" + blueprintId + "/overlay").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(overlay.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        MvcResult created = mvc.perform(post("/api/doccreation/instances").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"PG doc\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        int instanceId = JSON.readTree(created.getResponse().getContentAsString()).get("id").asInt();
        mvc.perform(put("/api/doccreation/instances/" + instanceId + "/values").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"full_name\":\"Jane\"}}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/doccreation/instances/" + instanceId + "/values").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"notes\":\"ok\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.full_name").value("Jane"));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        mvc.perform(multipart("/api/doccreation/instances/" + instanceId + "/images")
                        .file(new MockMultipartFile("files", "passport.png", "image/png", png.toByteArray()))
                        .header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigned[0].regionKey").value("passport_photo"));
        // ws33: validated (the row lock and the DocFieldRule read run under RLS) before it generates.
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/generate").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_VALIDATED"));
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/validate").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"))
                .andExpect(jsonPath("$.validation.checked").value(true));
        mvc.perform(post("/api/doccreation/instances/" + instanceId + "/generate").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exact").value(true));
        mvc.perform(get("/api/doccreation/instances/" + instanceId + "/download").header("Authorization", token))
                .andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM DocInstance WHERE DocInstanceId = " + instanceId
                + " AND GeneratedPdfKey IS NOT NULL")).isEqualTo(1);

        mvc.perform(delete("/api/doccreation/instances/" + instanceId).header("Authorization", token))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/doccreation/blueprints/" + blueprintId).header("Authorization", token))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM DocTemplateRegion WHERE BlueprintId = " + blueprintId)).isZero();
    }

    /** Read as the owner (bypasses RLS): the truth on disk. */
    private static long count(String query) throws Exception {
        try (Connection owner = ownerDataSource().getConnection();
             Statement sql = owner.createStatement();
             ResultSet rows = sql.executeQuery(query)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
