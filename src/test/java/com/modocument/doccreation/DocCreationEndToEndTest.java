package com.modocument.doccreation;

import com.modocument.doccreation.dto.AddBlockRequest;
import com.modocument.doccreation.dto.BlockDto;
import com.modocument.doccreation.dto.BlueprintDto;
import com.modocument.doccreation.dto.BulkUploadResultDto;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.dto.InstanceBuilderDto;
import com.modocument.doccreation.dto.SectionDto;
import com.modocument.doccreation.usecase.DocBlueprintUseCase;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole document-creation journey against the real wiring and schema:
 * upload a blueprint, tune keywords, build a document (bulk images with
 * auto-assignment, paragraphs, reorder, reassign), generate, verify the output
 * IS the blueprint plus the additions, prove cross-user isolation, and clean
 * everything up — files included. AI is disabled so the deterministic path
 * carries the run (the AI paths have their own suites); PDF asserts adapt to
 * whether LibreOffice exists on the machine.
 */
@SpringBootTest(properties = {
        "modocument.doccreation.section-ai-enabled=false",
        "modocument.doccreation.generate-ai-enabled=false",
})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.MethodName.class)
class DocCreationEndToEndTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("modocument.storage.root", () -> storageRoot.toString());
    }

    @Autowired
    private DocBlueprintUseCase blueprints;
    @Autowired
    private DocInstanceUseCase instances;

    @BeforeEach
    void authenticateAsUserOne() {
        authenticate(1);
    }

    private static void authenticate(int userId) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("bank_id", 1).claim("roles", List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- fixtures

    private static byte[] blueprintDocx() throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            text(doc, "MO Intelligence — Site Survey Report");
            heading(doc, "Roof Overview");
            text(doc, "Standard roof inspection notes.");
            heading(doc, "DC Routing (DC2)");
            text(doc, "Standard DC routing notes.");
            heading(doc, "Sign-off");
            text(doc, "Prepared by the engineering team.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void text(XWPFDocument doc, String value) {
        doc.createParagraph().createRun().setText(value);
    }

    private static void heading(XWPFDocument doc, String value) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading1");
        p.createRun().setText(value);
    }

    private static MockMultipartFile png(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("files", name, "image/png", out.toByteArray());
    }

    private static List<String> paragraphTexts(byte[] docx) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            List<String> texts = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    texts.add(p.getText());
                }
            }
            return texts;
        }
    }

    private BlueprintDto uploadBlueprint(String name) throws IOException {
        return blueprints.create(new MockMultipartFile("file", "survey report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                blueprintDocx()), name, null);
    }

    private static int sectionId(BlueprintDto blueprint, String title) {
        return blueprint.sections().stream().filter(s -> s.title().startsWith(title))
                .findFirst().orElseThrow().sectionId();
    }

    // --------------------------------------------------------------- journey

    @Test
    void journey_theWholeFlowFromBlueprintToGeneratedFilesAndCleanup() throws IOException {
        // 1. Upload: the document is broken into its three sections (heuristic
        //    engine — AI is off here) with keywords seeded from the titles.
        BlueprintDto blueprint = uploadBlueprint("PV Survey");
        assertThat(blueprint.sectionSource()).isEqualTo("HEURISTIC");
        assertThat(blueprint.sections()).extracting(SectionDto::title)
                .containsExactly("Roof Overview", "DC Routing (DC2)", "Sign-off");
        int roofId = sectionId(blueprint, "Roof");
        int dcId = sectionId(blueprint, "DC Routing");
        assertThat(blueprint.sections().get(1).keywords()).contains("dc2", "routing");

        // 2. Admin tunes keywords; a comma inside a keyword is dropped, case folds.
        SectionDto roof = blueprints.updateKeywords(blueprint.blueprintId(), roofId,
                List.of("Roof", "EAST", "a,b"));
        assertThat(roof.keywords()).containsExactly("roof", "east");

        // 3. A document instance on the blueprint; deleting the blueprint is
        //    now refused until the document goes.
        int instanceId = instances.create(blueprint.blueprintId(), "Survey — 12 Oak Street").instanceId();
        assertThatThrownBy(() -> blueprints.delete(blueprint.blueprintId()))
                .isInstanceOf(IllegalStateException.class);

        // 4. Bulk upload: two auto-assigned by keywords, one Unsorted, two
        //    rejected (wrong type / not a real image) — and two files sharing
        //    one name in the same request stay two distinct stored files.
        BulkUploadResultDto uploaded = instances.uploadAssets(instanceId, List.of(
                png("roof_east_01.png"),
                png("dc2_cable_run.png"),
                png("IMG_0999.png"),
                new MockMultipartFile("files", "notes.txt", "text/plain", "x".getBytes()),
                new MockMultipartFile("files", "broken.png", "image/png", "not an image".getBytes())),
                true);
        assertThat(uploaded.assigned()).extracting(BulkUploadResultDto.AssignedDto::sectionId)
                .containsExactlyInAnyOrder(roofId, dcId);
        assertThat(uploaded.unsorted()).hasSize(1);
        assertThat(uploaded.rejected()).hasSize(2);

        BulkUploadResultDto duplicates = instances.uploadAssets(instanceId,
                List.of(png("roof_twin.png"), png("roof_twin.png")), false);
        assertThat(duplicates.unsorted()).hasSize(2);
        List<InstanceBuilderDto.UnsortedAssetDto> tray = instances.getBuilder(instanceId).unsortedAssets();
        assertThat(tray).hasSize(3);
        // Distinct stored files: deleting one twin must not break the other.
        int twinA = duplicates.unsorted().get(0).assetId();
        int twinB = duplicates.unsorted().get(1).assetId();
        Path twinBFile = instances.getAssetContent(twinB).path();
        instances.deleteAsset(twinA);
        assertThat(Files.exists(twinBFile)).isTrue();
        instances.deleteAsset(twinB);

        // 5. Paragraphs in the roof section, reordered.
        AddBlockRequest first = new AddBlockRequest();
        first.setBlockType("PARAGRAPH");
        first.setText("Tiles on the east face need replacing.");
        AddBlockRequest second = new AddBlockRequest();
        second.setBlockType("PARAGRAPH");
        second.setText("Roof structure is sound.");
        BlockDto p1 = instances.addBlock(instanceId, roofId, first);
        BlockDto p2 = instances.addBlock(instanceId, roofId, second);
        // The image block auto-assigned to roof is also in this section.
        int roofImageBlock = uploaded.assigned().stream()
                .filter(a -> a.sectionId() == roofId).findFirst().orElseThrow().blockId();
        instances.reorderBlocks(instanceId, roofId,
                List.of(p2.blockId(), p1.blockId(), roofImageBlock));

        // 6. The Unsorted image is placed, moved between sections, unplaced.
        int unsortedAsset = uploaded.unsorted().get(0).assetId();
        instances.assignAsset(unsortedAsset, dcId);
        instances.assignAsset(unsortedAsset, roofId);
        instances.assignAsset(unsortedAsset, null);
        assertThat(instances.getBuilder(instanceId).unsortedAssets())
                .extracting(InstanceBuilderDto.UnsortedAssetDto::assetId)
                .containsExactly(unsortedAsset);

        // 7. Generate (deterministic engine here): the output IS the blueprint
        //    with the additions inside their sections, images embedded.
        GenerationResultDto generated = instances.generate(instanceId);
        assertThat(generated.composedBy()).isEqualTo("ENGINE");
        Path docxPath = storageRoot.resolve("exports").resolve(generated.docxFileName());
        assertThat(Files.exists(docxPath)).isTrue();
        List<String> texts = paragraphTexts(Files.readAllBytes(docxPath));
        assertThat(texts).containsSubsequence(
                "MO Intelligence — Site Survey Report",
                "Roof Overview", "Standard roof inspection notes.",
                "Roof structure is sound.", "Tiles on the east face need replacing.",
                "DC Routing (DC2)", "Standard DC routing notes.",
                "Sign-off", "Prepared by the engineering team.");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(Files.readAllBytes(docxPath)))) {
            // Two embedded picture references (roof + dc). POI stores identical
            // image bytes as one shared media part, so count references.
            long embedded = doc.getParagraphs().stream()
                    .flatMap(p -> p.getRuns().stream())
                    .mapToLong(r -> r.getEmbeddedPictures().size())
                    .sum();
            assertThat(embedded).isEqualTo(2);
        }
        // PDF depends on LibreOffice being installed on this machine; either
        // way is a valid production state and generate() never fails on it.
        if (generated.pdfReady()) {
            Path pdfPath = storageRoot.resolve("exports").resolve(generated.pdfFileName());
            byte[] pdf = Files.readAllBytes(pdfPath);
            assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        } else {
            assertThat(generated.pdfError()).isNotBlank();
        }

        // 8. Download names the file after the document; editing afterwards
        //    makes the next download regenerate.
        DocInstanceUseCase.DownloadFile download = instances.download(instanceId, "docx");
        assertThat(download.downloadName()).isEqualTo("Survey — 12 Oak Street.docx");
        AddBlockRequest late = new AddBlockRequest();
        late.setBlockType("PARAGRAPH");
        late.setText("Added after generation.");
        instances.addBlock(instanceId, roofId, late);
        instances.download(instanceId, "docx");
        assertThat(paragraphTexts(Files.readAllBytes(docxPath))).contains("Added after generation.");

        // 9. Another user sees none of it.
        authenticate(2);
        assertThatThrownBy(() -> instances.getBuilder(instanceId))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> blueprints.get(blueprint.blueprintId()))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(instances.list(null)).isEmpty();
        authenticate(1);

        // 10. Cleanup deletes rows AND files: assets, exports, blueprint file.
        List<Path> assetFiles = instances.getBuilder(instanceId).sections().stream()
                .flatMap(s -> s.blocks().stream())
                .filter(b -> b.assetId() != null)
                .map(b -> instances.getAssetContent(b.assetId()).path())
                .toList();
        instances.delete(instanceId);
        assertThat(assetFiles).allMatch(p -> !Files.exists(p));
        assertThat(Files.exists(docxPath)).isFalse();
        Path blueprintFile = blueprints.getFile(blueprint.blueprintId()).path();
        blueprints.delete(blueprint.blueprintId());
        assertThat(Files.exists(blueprintFile)).isFalse();
        assertThat(blueprints.list()).isEmpty();
    }

    @Test
    void rejections_theSharpEdgesAnswerCleanlyInsteadOfCorruptingState() throws IOException {
        // Invalid pdf/docx and unsupported types are rejected without leaving stored files.
        assertThatThrownBy(() -> blueprints.create(
                new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1}), null, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> blueprints.create(
                new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".docx and .pdf");
        assertThatThrownBy(() -> blueprints.create(
                new MockMultipartFile("file", "fake.docx", null, "not a zip".getBytes()), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        try (var files = Files.list(storageRoot.resolve("uploads"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("fake_"))).isEmpty();
        }

        BlueprintDto blueprint = uploadBlueprint("Edges");
        int roofId = sectionId(blueprint, "Roof");
        int instanceId = instances.create(blueprint.blueprintId(), "Edge doc").instanceId();

        // Wrong-shaped requests answer 400/404-shaped exceptions.
        AddBlockRequest badType = new AddBlockRequest();
        badType.setBlockType("TABLE");
        assertThatThrownBy(() -> instances.addBlock(instanceId, roofId, badType))
                .isInstanceOf(IllegalArgumentException.class);
        AddBlockRequest foreignSection = new AddBlockRequest();
        foreignSection.setBlockType("PARAGRAPH");
        assertThatThrownBy(() -> instances.addBlock(instanceId, 999_999, foreignSection))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> instances.reorderBlocks(instanceId, roofId, List.of(123)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> instances.download(instanceId, "exe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> instances.create(blueprint.blueprintId(), "  "))
                .isInstanceOf(IllegalArgumentException.class);

        // Image edge cases: 0-byte and .webp rejected; a PNG renamed .jpg is
        // accepted with its TRUE content type (byte-sniffed).
        BulkUploadResultDto edges = instances.uploadAssets(instanceId, List.of(
                new MockMultipartFile("files", "empty.png", "image/png", new byte[0]),
                new MockMultipartFile("files", "modern.webp", "image/webp", new byte[]{1, 2, 3}),
                new MockMultipartFile("files", "actually_png.jpg", "image/jpeg",
                        png("x.png").getBytes())), false);
        assertThat(edges.rejected()).hasSize(2);
        assertThat(edges.unsorted()).hasSize(1);
        assertThat(instances.getAssetContent(edges.unsorted().get(0).assetId()).contentType())
                .isEqualTo("image/png");

        // Over-long inputs answer 400, not a database error.
        assertThatThrownBy(() -> instances.create(blueprint.blueprintId(), "t".repeat(300)))
                .isInstanceOf(IllegalArgumentException.class);

        // Generating a document with no blocks still reproduces the blueprint.
        GenerationResultDto generated = instances.generate(instanceId);
        Path docxPath = storageRoot.resolve("exports").resolve(generated.docxFileName());
        assertThat(paragraphTexts(Files.readAllBytes(docxPath)))
                .contains("Roof Overview", "Sign-off");

        instances.delete(instanceId);
        blueprints.delete(blueprint.blueprintId());
    }
}
