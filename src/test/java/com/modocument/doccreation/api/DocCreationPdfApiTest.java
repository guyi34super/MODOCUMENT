package com.modocument.doccreation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.doccreation.pdftemplate.EditKind;
import com.modocument.doccreation.pdftemplate.EditSource;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.RegionSource;
import com.modocument.doccreation.pdftemplate.TemplateEdit;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.usecase.RemovalPolicy;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every endpoint of the exact PDF templates (docs/doccreation/pdf-templates-contract.md
 * §5) over real HTTP semantics, on the steerable FakePdfTemplateEngine:
 * upload (PDF by magic bytes, DOCX 400, encrypted 400), DRAFT review data
 * that is gone after confirm, the fraction mapping with page rotation,
 * versions with the optimistic 409, page previews (cached) and files,
 * template images, document values (merged), photos (auto-fill by name then
 * reading order, drag with swap), previews, generation (exactness refusal
 * 500 + audit), download with stale detection, and cross-user isolation.
 * The legacy purge and 410 have their own suite (DocLegacyPurgeApiTest).
 */
class DocCreationPdfApiTest extends IsolatedStorageApiTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private FakePdfTemplateEngine engine;

    private Bank bank;
    private AppUser owner;
    private AppUser other;
    private final List<Integer> userIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine.reset();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        bank = banks.save(new Bank("PDF" + tag.substring(0, 5).toUpperCase(), "PDF templates bank"));
        owner = user("pdf-owner-" + tag, Role.READ_WRITE);
        other = user("pdf-other-" + tag, Role.READ_WRITE);
    }

    @AfterEach
    void cleanUp() {
        int bankId = bank.getBankId();
        jdbc.update("DELETE FROM DocInstanceImage WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocImageAsset WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocInstance WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocTemplateRegion WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocTemplateEdit WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocBlueprintVersion WHERE BankId = ?", bankId);
        jdbc.update("DELETE FROM DocBlueprint WHERE BankId = ?", bankId);
        userIds.forEach(users::deleteById);
        userIds.clear();
        banks.delete(bank);
    }

    // ------------------------------------------------------------ fixtures

    private AppUser user(String name, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(name + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(name);
        entity.setBankId(bank.getBankId());
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

    private JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] bytes, String fileName, String flow) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/doccreation/blueprints");
        request.file(new MockMultipartFile("file", fileName, "application/pdf", bytes));
        if (flow != null) {
            request.param("flow", flow);
        }
        return request;
    }

    private JsonNode uploadDraft(byte[] pdf, String flow) throws Exception {
        return json(mvc.perform(as(upload(pdf, "form.pdf", flow).param("name", "Form"), owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn());
    }

    private JsonNode saveOverlay(int id, Integer baseVersion, JsonNode regions, JsonNode edits, int expected)
            throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        if (baseVersion != null) {
            body.put("baseVersion", baseVersion);
        }
        body.set("regions", regions);
        body.set("edits", edits == null ? MAPPER.createArrayNode() : edits);
        body.put("confirm", true);
        MvcResult result = mvc.perform(as(put("/api/doccreation/blueprints/" + id + "/overlay"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().is(expected))
                .andReturn();
        return json(result);
    }

    private int readyTemplate() throws Exception {
        JsonNode draft = uploadDraft(FakePdfTemplateEngine.blankPdf(1, 0), "BLANK");
        return saveOverlay(draft.get("id").asInt(), 1, draft.get("regions"), draft.get("edits"), 200)
                .get("id").asInt();
    }

    private int document(int blueprintId) throws Exception {
        return json(mvc.perform(as(post("/api/doccreation/instances"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + blueprintId + ",\"title\":\"Loan file\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instanceId").exists())
                .andReturn()).get("id").asInt();
    }

    private JsonNode putValues(int instanceId, String valuesJson, int expected) throws Exception {
        return json(mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/values"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":" + valuesJson + "}"))
                .andExpect(status().is(expected))
                .andReturn());
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private int audits(String action, String outcome, String entityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM AuditEvent WHERE Action = ? AND Outcome = ? "
                + "AND EntityId = ? AND BankId = ?", Integer.class, action, outcome, entityId, bank.getBankId());
    }

    // --------------------------------------------------------------- upload

    @Test
    void onlyRealPdfsAreAcceptedWhateverTheirName() throws Exception {
        // A .docx (a ZIP) is refused by its bytes, even when named .pdf.
        byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};
        mvc.perform(as(upload(zip, "report.docx", null), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_PDF"));
        mvc.perform(as(upload(zip, "report.pdf", null), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_A_PDF"));
        // A real PDF is accepted by its bytes, whatever its name.
        mvc.perform(as(upload(FakePdfTemplateEngine.blankPdf(1, 0), "scan.bin", "BLANK"), owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.flow").value("BLANK"));
        // A password-protected PDF answers with the engine's code.
        mvc.perform(as(upload(encryptedPdf(), "secret.pdf", null), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENCRYPTED"));
        mvc.perform(as(upload(FakePdfTemplateEngine.blankPdf(1, 0), "a.pdf", "SIDEWAYS"), owner))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocBlueprint WHERE BankId = ?", Integer.class,
                bank.getBankId())).as("only the one real PDF was stored").isEqualTo(1);
    }

    private static byte[] encryptedPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aFilledUploadShowsItsSampleDataOnlyUntilTheTemplateIsConfirmed() throws Exception {
        engine.looksFilled = true;
        JsonNode draft = uploadDraft(FakePdfTemplateEngine.blankPdf(1, 0), "AUTO");
        int id = draft.get("id").asInt();
        assertThat(draft.get("flow").asText()).isEqualTo("WITH_DATA");
        assertThat(draft.get("regions").get(0).get("sampleValue").asText()).isEqualTo("John Specimen");
        assertThat(draft.get("edits").get(0).get("removedText").asText()).isEqualTo("John Specimen");
        assertThat(draft.get("edits").get(0).get("kind").asText()).isEqualTo("REMOVE");
        assertThat(draft.get("warnings")).isNotEmpty();
        assertThat(draft.get("detectedBy").asText()).isEqualTo("CODE");
        assertThat(engine.lastAnalysisOptions.aiAllowed()).as("template AI is off in this context").isFalse();
        Path draftFile = STORAGE_ROOT.resolve("doccreation/b" + bank.getBankId() + "/blueprint" + id + "/draft.json");
        assertThat(draftFile).exists();
        assertThat(Files.readString(draftFile)).contains("John Specimen");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocTemplateRegion WHERE BlueprintId = ?",
                Integer.class, id)).isEqualTo(4);
        assertThat(audits("DOC_BLUEPRINT_CREATED", "SUCCESS", String.valueOf(id))).isOne();

        // A document cannot be started on a draft.
        mvc.perform(as(post("/api/doccreation/instances"), owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blueprintId\":" + id + ",\"title\":\"Too early\"}"))
                .andExpect(status().isConflict());

        JsonNode ready = saveOverlay(id, 1, draft.get("regions"), draft.get("edits"), 200);
        assertThat(ready.get("status").asText()).isEqualTo("READY");
        assertThat(ready.get("version").asInt()).isEqualTo(1);
        assertThat(ready.toString()).doesNotContain("John Specimen").doesNotContain("sampleValue")
                .doesNotContain("removedText");
        assertThat(ready.get("warnings")).isEmpty();
        // The template is rebuilt from the ORIGINAL with the edits; AcroForm
        // values are cleared in the WITH_DATA flow.
        assertThat(engine.lastEdits).hasSize(1);
        assertThat(engine.lastClearAcroForm).isTrue();
        assertThat(draftFile).doesNotExist();
        assertThat(jdbc.queryForList("SELECT * FROM DocTemplateRegion WHERE BlueprintId = ?", id).toString())
                .doesNotContain("John Specimen");
        assertThat(audits("DOC_BLUEPRINT_VERSION_SAVED", "SUCCESS", String.valueOf(id))).isOne();
        mvc.perform(as(get("/api/doccreation/blueprints/" + id), owner))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("John Specimen"))));

        mvc.perform(as(get("/api/doccreation/blueprints"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].pageCount").value(1))
                .andExpect(jsonPath("$[0].flow").value("WITH_DATA"));
    }

    /** A filled upload whose engine proposes one confident removal and one low-confidence guess. */
    private JsonNode uploadWithALowConfidenceGuess() throws Exception {
        engine.looksFilled = true;
        List<TemplateRegion> regions = new ArrayList<>(FakePdfTemplateEngine.defaultRegions());
        TemplateRegion contact = FakePdfTemplateEngine.region("contact", "Contact", null, RegionKind.TEXT,
                new PdfBox(0, 300, 300, 150, 14), 4, "jane@example.com");
        regions.add(new TemplateRegion(contact.key(), contact.label(), contact.group(), contact.kind(),
                contact.box(), contact.fontName(), contact.fontSize(), contact.color(), contact.align(),
                contact.maxLines(), null, false, 4, RegionSource.DATA_REMOVED, 0.45, contact.sampleValue()));
        engine.proposedRegions = regions;
        List<TemplateEdit> removals = new ArrayList<>(FakePdfTemplateEngine.defaultRemovals());
        removals.add(new TemplateEdit("rm-low", EditKind.REMOVE, new PdfBox(0, 300, 300, 150, 14), null, null,
                null, null, null, EditSource.DATA_REMOVAL, 0.45, "jane@example.com", "contact"));
        engine.proposedRemovals = removals;
        return uploadDraft(FakePdfTemplateEngine.blankPdf(1, 0), "AUTO");
    }

    private static JsonNode byField(JsonNode list, String field, String value) {
        for (JsonNode node : list) {
            if (value.equals(node.path(field).asText(null))) {
                return node;
            }
        }
        return null;
    }

    /** BUG-PDF-01: a guess below RemovalPolicy.APPLY_THRESHOLD is shown as a suggestion, never applied by default. */
    @Test
    void aLowConfidenceGuessIsSuggestedButNotAppliedUnlessTheClientAppliesIt() throws Exception {
        assertThat(RemovalPolicy.APPLY_THRESHOLD).isEqualTo(0.6);
        JsonNode draft = uploadWithALowConfidenceGuess();
        int id = draft.get("id").asInt();

        // Only the confident removal is applied to the draft template.
        assertThat(engine.lastEdits).extracting(TemplateEdit::id).containsExactly("rm-1");
        JsonNode applied = byField(draft.get("edits"), "id", "rm-1");
        assertThat(applied.get("applied").asBoolean()).isTrue();
        assertThat(applied.get("regionKey").asText()).isEqualTo("full_name");
        JsonNode suggestion = byField(draft.get("edits"), "id", "rm-low");
        assertThat(suggestion.get("applied").asBoolean()).isFalse();
        assertThat(suggestion.get("confidence").asDouble()).isEqualTo(0.45);
        assertThat(suggestion.get("removedText").asText()).isEqualTo("jane@example.com");
        assertThat(suggestion.get("regionKey").asText()).isEqualTo("contact");
        assertThat(suggestion.get("source").asText()).isEqualTo("DATA_REMOVAL");
        // Its field is returned flagged as suggested; the others are not.
        assertThat(byField(draft.get("regions"), "key", "contact").get("suggested").asBoolean()).isTrue();
        assertThat(byField(draft.get("regions"), "key", "contact").get("sampleValue").asText())
                .isEqualTo("jane@example.com");
        assertThat(byField(draft.get("regions"), "key", "full_name").get("suggested").asBoolean()).isFalse();
        // Neither is stored in a row: review data lives only in draft.json.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocTemplateRegion WHERE BlueprintId = ?",
                Integer.class, id)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT EditId FROM DocTemplateEdit WHERE BlueprintId = ?", String.class, id))
                .containsExactly("rm-1");
        // The GET shows the same.
        JsonNode reread = json(mvc.perform(as(get("/api/doccreation/blueprints/" + id), owner))
                .andExpect(status().isOk()).andReturn());
        assertThat(byField(reread.get("edits"), "id", "rm-low").get("applied").asBoolean()).isFalse();
        assertThat(reread.get("edits")).hasSize(2);
        assertThat(reread.get("regions")).hasSize(5);

        // Confirming WITHOUT review (everything sent back as received): the guess is ignored, not refused.
        JsonNode ready = saveOverlay(id, 1, draft.get("regions"), draft.get("edits"), 200);
        assertThat(engine.lastEdits).extracting(TemplateEdit::id).containsExactly("rm-1");
        assertThat(ready.get("edits")).hasSize(1);
        assertThat(ready.get("edits").get(0).get("applied").asBoolean()).isTrue();
        assertThat(ready.get("edits").get(0).has("regionKey")).as("linkage is draft review data").isFalse();
        assertThat(byField(ready.get("regions"), "key", "contact")).isNull();
        assertThat(ready.get("regions")).hasSize(4);
        assertThat(ready.toString()).doesNotContain("jane@example.com");
    }

    @Test
    void anAppliedSuggestionIsAppliedWithItsField() throws Exception {
        JsonNode draft = uploadWithALowConfidenceGuess();
        int id = draft.get("id").asInt();
        ArrayNode edits = (ArrayNode) draft.get("edits").deepCopy();
        edits.forEach(e -> ((ObjectNode) e).put("applied", true));
        ArrayNode regions = (ArrayNode) draft.get("regions").deepCopy();
        regions.forEach(r -> ((ObjectNode) r).put("suggested", false));

        // A draft save (not confirmed) keeps the review data: the applied suggestion is listed once, applied.
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseVersion", 1);
        body.set("regions", regions);
        body.set("edits", edits);
        body.put("confirm", false);
        JsonNode saved = json(mvc.perform(as(put("/api/doccreation/blueprints/" + id + "/overlay"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("status").asText()).isEqualTo("DRAFT");
        assertThat(engine.lastEdits).extracting(TemplateEdit::id).containsExactlyInAnyOrder("rm-1", "rm-low");
        assertThat(saved.get("edits")).hasSize(2);
        JsonNode nowApplied = byField(saved.get("edits"), "id", "rm-low");
        assertThat(nowApplied.get("applied").asBoolean()).isTrue();
        assertThat(nowApplied.get("removedText").asText()).isEqualTo("jane@example.com");
        assertThat(nowApplied.get("confidence").asDouble()).isEqualTo(0.45);
        assertThat(nowApplied.get("regionKey").asText()).isEqualTo("contact");
        assertThat(saved.get("regions")).hasSize(5);
        assertThat(byField(saved.get("regions"), "key", "contact").get("suggested").asBoolean()).isFalse();

        // Confirming applies exactly what the client sent.
        JsonNode ready = saveOverlay(id, 1, regions, edits, 200);
        assertThat(engine.lastEdits).extracting(TemplateEdit::id).containsExactlyInAnyOrder("rm-1", "rm-low");
        assertThat(ready.get("edits")).hasSize(2);
        assertThat(byField(ready.get("regions"), "key", "contact")).isNotNull();
    }

    @Test
    void aDismissedSuggestionAndAKeptRegionWithoutItsRemoval() throws Exception {
        JsonNode draft = uploadWithALowConfidenceGuess();
        int id = draft.get("id").asInt();
        // The client dismissed the suggestion (not sent) and restored the confident removal, dropping its field.
        ArrayNode edits = MAPPER.createArrayNode();
        ArrayNode regions = MAPPER.createArrayNode();
        draft.get("regions").forEach(r -> {
            if (!r.get("suggested").asBoolean() && !"full_name".equals(r.get("key").asText())) {
                regions.add(r);
            }
        });
        JsonNode ready = saveOverlay(id, 1, regions, edits, 200);
        assertThat(engine.lastEdits).isEmpty();
        assertThat(ready.get("edits")).isEmpty();
        assertThat(ready.get("regions")).extracting(r -> r.get("key").asText())
                .containsExactlyInAnyOrder("notes", "passport_photo", "house_front");
    }

    // ------------------------------------------------------------ geometry

    @Test
    void fractionsFollowThePageRotationBothWays() throws Exception {
        // A4 portrait, displayed rotated 90 degrees clockwise.
        engine.proposedRegions = List.of(FakePdfTemplateEngine.region("box", "Box", null, RegionKind.TEXT,
                new PdfBox(0, 100, 700, 200, 20), 0, null));
        JsonNode draft = uploadDraft(FakePdfTemplateEngine.blankPdf(1, 90), "BLANK");
        JsonNode page = draft.get("pages").get(0);
        assertThat(page.get("rotation").asInt()).isEqualTo(90);
        double width = page.get("width").asDouble();
        double height = page.get("height").asDouble();
        JsonNode box = draft.get("regions").get(0);
        // Unrotated: ux = 100/W, uy = 1 - 720/H, uw = 200/W, uh = 20/H;
        // 90 deg clockwise: (fx, fy, fw, fh) = (1 - (uy + uh), ux, uh, uw).
        double uy = 1 - 720 / height;
        double uh = 20 / height;
        assertThat(box.get("fx").asDouble()).isCloseTo(1 - (uy + uh), within(1e-6));
        assertThat(box.get("fy").asDouble()).isCloseTo(100 / width, within(1e-6));
        assertThat(box.get("fw").asDouble()).isCloseTo(uh, within(1e-6));
        assertThat(box.get("fh").asDouble()).isCloseTo(200 / width, within(1e-6));

        // The fractions come back to the same box in points.
        int id = draft.get("id").asInt();
        saveOverlay(id, 1, draft.get("regions"), null, 200);
        var row = jdbc.queryForMap("SELECT X, Y, W, H FROM DocTemplateRegion WHERE BlueprintId = ?", id);
        assertThat(((Number) row.get("X")).doubleValue()).isCloseTo(100, within(0.01));
        assertThat(((Number) row.get("Y")).doubleValue()).isCloseTo(700, within(0.01));
        assertThat(((Number) row.get("W")).doubleValue()).isCloseTo(200, within(0.01));
        assertThat(((Number) row.get("H")).doubleValue()).isCloseTo(20, within(0.01));
    }

    @Test
    void anInvalidOverlayIsRefusedWithoutANewVersion() throws Exception {
        JsonNode draft = uploadDraft(FakePdfTemplateEngine.blankPdf(1, 0), "BLANK");
        int id = draft.get("id").asInt();
        ArrayNode outside = draft.get("regions").deepCopy();
        ((ObjectNode) outside.get(0)).put("fx", 0.95).put("fw", 0.2);
        saveOverlay(id, 1, outside, null, 400);
        ArrayNode duplicate = draft.get("regions").deepCopy();
        ((ObjectNode) duplicate.get(1)).put("key", duplicate.get(0).get("key").asText());
        saveOverlay(id, 1, duplicate, null, 400);
        ArrayNode badKey = draft.get("regions").deepCopy();
        ((ObjectNode) badKey.get(0)).put("key", "Full Name!");
        saveOverlay(id, 1, badKey, null, 400);
        ArrayNode badKind = draft.get("regions").deepCopy();
        ((ObjectNode) badKind.get(0)).put("kind", "SPREADSHEET");
        saveOverlay(id, 1, badKind, null, 400);
        ArrayNode wrongPage = draft.get("regions").deepCopy();
        ((ObjectNode) wrongPage.get(0)).put("page", 3);
        saveOverlay(id, 1, wrongPage, null, 400);
        mvc.perform(as(get("/api/doccreation/blueprints/" + id), owner))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // ------------------------------------------------------------ versions

    @Test
    void editsAreNewVersionsAndAStaleBaseIsAConflict() throws Exception {
        int id = readyTemplate();
        JsonNode v1 = json(mvc.perform(as(get("/api/doccreation/blueprints/" + id), owner)).andReturn());
        int documentOnV1 = document(id);

        // An editor-made region (as the frontend sends it) and a text edit.
        ArrayNode regions = v1.get("regions").deepCopy();
        ObjectNode added = regions.addObject();
        added.put("key", "signature_date").put("label", "Date").put("kind", "DATE").put("page", 0)
                .put("fx", 0.1).put("fy", 0.9).put("fw", 0.2).put("fh", 0.02)
                .put("fontName", "Helvetica").put("fontSize", 0).put("source", "MANUAL");
        ArrayNode edits = MAPPER.createArrayNode();
        edits.addObject().put("id", UUID.randomUUID().toString()).put("kind", "ADD_TEXT").put("page", 0)
                .put("fx", 0.5).put("fy", 0.05).put("fw", 0.3).put("fh", 0.02).put("text", "Branch copy")
                .put("source", "USER");
        JsonNode v2 = saveOverlay(id, 1, regions, edits, 200);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("currentVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("regions")).hasSize(5);
        assertThat(v2.get("edits").get(0).get("text").asText()).isEqualTo("Branch copy");

        // Someone still on version 1 saves: 409, nothing written.
        saveOverlay(id, 1, regions, edits, 409);
        mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/versions"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].regionCount").value(5))
                .andExpect(jsonPath("$[0].editCount").value(1))
                .andExpect(jsonPath("$[0].sectionSource").value("MANUAL"))
                .andExpect(jsonPath("$[1].documentCount").value(1));
        // The older version stays readable; the document keeps building on it.
        mvc.perform(as(get("/api/doccreation/blueprints/" + id).param("version", "1"), owner))
                .andExpect(jsonPath("$.regions.length()").value(4));
        mvc.perform(as(get("/api/doccreation/blueprints/" + id).param("version", "9"), owner))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/doccreation/instances/" + documentOnV1), owner))
                .andExpect(jsonPath("$.blueprintVersion").value(1))
                .andExpect(jsonPath("$.blueprintCurrentVersion").value(2))
                .andExpect(jsonPath("$.regions.length()").value(4));
    }

    // ------------------------------------------------------ pages and files

    @Test
    void pagePreviewsAreCachedPngsAndFilesArePdfs() throws Exception {
        byte[] original = FakePdfTemplateEngine.blankPdf(2, 0);
        JsonNode draft = uploadDraft(original, "BLANK");
        int id = draft.get("id").asInt();

        for (int i = 0; i < 2; i++) {
            mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/pages/1").param("dpi", "72"), owner))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG));
        }
        assertThat(engine.renders.get()).as("the second request is served from the cache").isEqualTo(1);
        mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/pages/0")
                        .param("variant", "original").param("dpi", "72"), owner))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/pages/2"), owner))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/pages/0").param("variant", "x"), owner))
                .andExpect(status().isBadRequest());

        MvcResult file = mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/file")
                        .param("variant", "original"), owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(original);
        mvc.perform(as(get("/api/doccreation/blueprints/" + id + "/file"), owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

    @Test
    void aTemplateImageCanBeAddedAsAnEdit() throws Exception {
        int id = readyTemplate();
        MvcResult uploaded = mvc.perform(as(multipart("/api/doccreation/blueprints/" + id + "/edit-images")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", png())), owner))
                .andExpect(status().isOk())
                .andReturn();
        String imageKey = json(uploaded).get("imageKey").asText();
        assertThat(imageKey).startsWith("img_");
        mvc.perform(as(multipart("/api/doccreation/blueprints/" + id + "/edit-images")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", "nope".getBytes())), owner))
                .andExpect(status().isBadRequest());

        JsonNode v1 = json(mvc.perform(as(get("/api/doccreation/blueprints/" + id), owner)).andReturn());
        ArrayNode edits = MAPPER.createArrayNode();
        edits.addObject().put("kind", "ADD_IMAGE").put("page", 0).put("fx", 0.05).put("fy", 0.02)
                .put("fw", 0.2).put("fh", 0.08).put("imageKey", imageKey);
        saveOverlay(id, 1, v1.get("regions"), edits, 200);
        assertThat(engine.lastEditImages).containsKey(imageKey);

        String neverUploaded = "img_" + "0".repeat(32);
        ((ObjectNode) edits.get(0)).put("imageKey", neverUploaded);
        saveOverlay(id, 2, v1.get("regions"), edits, 400);
    }

    // -------------------------------------------------------------- values

    @Test
    void valuesMergePerKeyAndUnknownKeysAreRefused() throws Exception {
        int instanceId = document(readyTemplate());

        putValues(instanceId, "{\"full_name\":\"Jane Citizen\"}", 200);
        JsonNode both = putValues(instanceId, "{\"notes\":\"All good\"}", 200);
        assertThat(both.get("values").get("full_name").asText()).as("not wiped by the second save")
                .isEqualTo("Jane Citizen");
        assertThat(both.get("values").get("notes").asText()).isEqualTo("All good");

        JsonNode cleared = putValues(instanceId, "{\"notes\":\"\"}", 200);
        assertThat(cleared.get("values").has("notes")).isFalse();
        assertThat(cleared.get("values").get("full_name").asText()).isEqualTo("Jane Citizen");

        putValues(instanceId, "{\"no_such_field\":\"x\"}", 400);
        putValues(instanceId, "{\"passport_photo\":\"x\"}", 400);
        assertThat(cleared.get("regions").get(0).has("sampleValue")).isFalse();
    }

    // -------------------------------------------------------------- photos

    @Test
    void photosAutoFillByNameThenReadingOrderAndSwapWhenDragged() throws Exception {
        int instanceId = document(readyTemplate());
        MvcResult result = mvc.perform(as(multipart("/api/doccreation/instances/" + instanceId + "/images")
                        .file(new MockMultipartFile("files", "IMG_0001.png", "image/png", png()))
                        .file(new MockMultipartFile("files", "passport-scan.png", "image/png", png()))
                        .file(new MockMultipartFile("files", "extra.png", "image/png", png()))
                        .file(new MockMultipartFile("files", "notes.txt", "text/plain", "x".getBytes()))
                        .param("autoFill", "true"), owner))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode upload = json(result);
        assertThat(upload.get("rejected")).hasSize(1);
        assertThat(upload.get("rejected").get(0).get("name").asText()).isEqualTo("notes.txt");
        assertThat(upload.get("assigned")).hasSize(2);
        assertThat(upload.get("unassigned")).hasSize(1);

        JsonNode doc = json(mvc.perform(as(get("/api/doccreation/instances/" + instanceId), owner)).andReturn());
        int passport = imageId(doc, "passport-scan.png");
        int generic = imageId(doc, "IMG_0001.png");
        int extra = imageId(doc, "extra.png");
        assertThat(regionOf(doc, passport)).as("name match").isEqualTo("passport_photo");
        assertThat(regionOf(doc, generic)).as("reading order").isEqualTo("house_front");
        assertThat(regionOf(doc, extra)).as("no box left: the tray").isNull();

        // Drag the tray photo onto the filled passport box: the two swap.
        JsonNode swapped = json(mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/images/" + extra),
                        owner).contentType(MediaType.APPLICATION_JSON).content("{\"regionKey\":\"passport_photo\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(regionOf(swapped, extra)).isEqualTo("passport_photo");
        assertThat(regionOf(swapped, passport)).as("swapped back to where the dragged one was").isNull();
        // Back to the tray, and onto a field that is not a photo box.
        JsonNode tray = json(mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/images/" + extra),
                        owner).contentType(MediaType.APPLICATION_JSON).content("{\"regionKey\":null}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(regionOf(tray, extra)).isNull();
        mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/images/" + extra), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"regionKey\":\"full_name\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/images/" + generic + "/content"), owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(as(delete("/api/doccreation/instances/" + instanceId + "/images/" + extra), owner))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/images/" + extra + "/content"), owner))
                .andExpect(status().isNotFound());
        // Another user cannot reach the photos.
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/images/" + generic + "/content"), other))
                .andExpect(status().isNotFound());
    }

    private static int imageId(JsonNode doc, String name) {
        for (JsonNode image : doc.get("images")) {
            if (image.get("originalName").asText().equals(name)) {
                return image.get("id").asInt();
            }
        }
        throw new AssertionError("no image " + name);
    }

    private static String regionOf(JsonNode doc, int imageId) {
        for (JsonNode image : doc.get("images")) {
            if (image.get("id").asInt() == imageId) {
                return image.get("regionKey").isNull() ? null : image.get("regionKey").asText();
            }
        }
        throw new AssertionError("no image " + imageId);
    }

    // ---------------------------------------------------------- generation

    @Test
    void generationDrawsTheValuesAndDownloadRegeneratesOnlyWhenStale() throws Exception {
        int instanceId = document(readyTemplate());
        engine.continueOver = 40;
        String longNotes = "This note is far too long to fit its box at the minimum size, so it continues "
                + "on an added page.";
        JsonNode doc = putValues(instanceId, "{\"full_name\":\"Jane Citizen\",\"notes\":\""
                + "x ".repeat(300) + "\"}", 200);
        assertThat(doc.get("overflow").toString()).as("the builder's overflow warning").contains("notes");
        putValues(instanceId, "{\"notes\":\"" + longNotes + "\"}", 200);
        mvc.perform(as(multipart("/api/doccreation/instances/" + instanceId + "/images")
                        .file(new MockMultipartFile("files", "house_front.png", "image/png", png())), owner))
                .andExpect(status().isCreated());

        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/pages/0").param("dpi", "72"), owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        assertThat(engine.lastFillOptions.verify()).as("previews skip the exactness check").isFalse();

        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pdfReady").value(true))
                .andExpect(jsonPath("$.exact").value(true))
                .andExpect(jsonPath("$.addedPages").value(1))
                .andExpect(jsonPath("$.continuedKeys[0]").value("notes"))
                .andExpect(jsonPath("$.generatedDate").exists());
        assertThat(engine.lastFillOptions.minFontSize()).isEqualTo(6f);
        assertThat(engine.lastFillOptions.continuationPages()).isTrue();
        assertThat(engine.lastFillOptions.verify()).isTrue();
        assertThat(engine.lastFillImages).containsKey("house_front");
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId), owner))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.pdfAvailable").value(true));

        MvcResult download = mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download"), owner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(download.getResponse().getHeader("Content-Disposition")).contains("Loan file.pdf");
        assertThat(pdfText(download.getResponse().getContentAsByteArray())).contains("full_name=Jane Citizen");
        assertThat(engine.lastFillOptions.verify()).isTrue();
        engine.lastFillValues = null;
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download"), owner))
                .andExpect(status().isOk());
        assertThat(engine.lastFillValues).as("fresh output is served, not regenerated").isNull();

        // A change makes the output stale: the download regenerates.
        Thread.sleep(5);
        putValues(instanceId, "{\"full_name\":\"Jane Q. Citizen\"}", 200);
        MvcResult fresh = mvc.perform(as(get("/api/doccreation/instances/" + instanceId + "/download"), owner))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(pdfText(fresh.getResponse().getContentAsByteArray())).contains("full_name=Jane Q. Citizen");
        assertThat(audits("DOC_DOWNLOADED", "SUCCESS", String.valueOf(instanceId))).isEqualTo(3);
    }

    @Test
    void anOutputThatFailsTheExactnessCheckIsRefusedAndAudited() throws Exception {
        int instanceId = document(readyTemplate());
        putValues(instanceId, "{\"full_name\":\"Jane\"}", 200);
        engine.failExactness = true;
        // The shared test storage may still hold an earlier context's file for this id.
        Path output = STORAGE_ROOT.resolve("exports/doccreation-instance" + instanceId + ".pdf");
        Files.deleteIfExists(output);

        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate"), owner))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("EXACTNESS_FAILED"));

        assertThat(audits("DOC_GENERATED", "FAILED", String.valueOf(instanceId))).isOne();
        assertThat(audits("DOC_GENERATED", "SUCCESS", String.valueOf(instanceId))).isZero();
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId), owner))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.pdfAvailable").value(false));
        assertThat(output).as("no output stored").doesNotExist();
        assertThat(jdbc.queryForObject("SELECT GeneratedPdfKey FROM DocInstance WHERE DocInstanceId = ?",
                String.class, instanceId)).isNull();
    }

    // ------------------------------------------------- isolation and delete

    @Test
    void anotherUserSeesNothingAndDeletionRemovesRowsAndFiles() throws Exception {
        int blueprintId = readyTemplate();
        int instanceId = document(blueprintId);
        mvc.perform(as(get("/api/doccreation/blueprints/" + blueprintId), other)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/doccreation/blueprints/" + blueprintId + "/pages/0"), other))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/doccreation/instances/" + instanceId), other)).andExpect(status().isNotFound());
        putValuesAs(other, instanceId);
        mvc.perform(as(get("/api/doccreation/instances"), other))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/doccreation/instances").param("blueprintId", String.valueOf(blueprintId)), owner))
                .andExpect(jsonPath("$[0].id").value(instanceId))
                .andExpect(jsonPath("$[0].instanceId").value(instanceId))
                .andExpect(jsonPath("$[0].pageCount").value(1));

        mvc.perform(as(delete("/api/doccreation/blueprints/" + blueprintId), owner))
                .andExpect(status().isConflict());
        mvc.perform(as(post("/api/doccreation/instances/" + instanceId + "/generate"), owner))
                .andExpect(status().isOk());
        Path output = STORAGE_ROOT.resolve("exports/doccreation-instance" + instanceId + ".pdf");
        assertThat(output).exists();
        mvc.perform(as(delete("/api/doccreation/instances/" + instanceId), owner))
                .andExpect(status().isNoContent());
        assertThat(output).doesNotExist();

        Path prefix = STORAGE_ROOT.resolve("doccreation/b" + bank.getBankId() + "/blueprint" + blueprintId);
        assertThat(prefix.resolve("original.pdf")).exists();
        mvc.perform(as(delete("/api/doccreation/blueprints/" + blueprintId), owner))
                .andExpect(status().isNoContent());
        assertThat(prefix.resolve("original.pdf")).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DocTemplateRegion WHERE BlueprintId = ?",
                Integer.class, blueprintId)).isZero();
    }

    private void putValuesAs(AppUser user, int instanceId) throws Exception {
        mvc.perform(as(put("/api/doccreation/instances/" + instanceId + "/values"), user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"values\":{\"full_name\":\"x\"}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aReadOnlyUserCanLookButNotChange() throws Exception {
        int blueprintId = readyTemplate();
        AppUser reader = user("pdf-reader-" + UUID.randomUUID().toString().substring(0, 6), Role.READ_ONLY);
        mvc.perform(as(upload(FakePdfTemplateEngine.blankPdf(1, 0), "a.pdf", null), reader))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/doccreation/blueprints/" + blueprintId + "/overlay"), reader)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"regions\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/doccreation/blueprints"), reader)).andExpect(status().isOk());
    }
}
