package com.modocument.doccreation.generate;

import com.modocument.doccreation.entity.DocBlueprintSection;
import com.modocument.doccreation.entity.DocContentBlock;
import com.modocument.doccreation.entity.DocImageAsset;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip proof of the "reproduce the document in that exact manner"
 * guarantee: every original paragraph survives in order, and inserted content
 * lands strictly inside its own section.
 */
class DocumentAssemblerTest {

    private static final int MAX_WIDTH_EMU = (int) (6.5 * 914400);

    @TempDir
    Path tempDir;

    private final DocumentAssembler assembler = new DocumentAssembler();
    private Path blueprint;
    private DocBlueprintSection roof;
    private DocBlueprintSection inverter;

    @BeforeEach
    void buildBlueprint() throws IOException {
        blueprint = tempDir.resolve("blueprint.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            text(doc, "Company letterhead line.");   // 0
            heading(doc, "Roof");                    // 1
            text(doc, "Existing roof paragraph.");   // 2
            heading(doc, "Inverter");                // 3
            text(doc, "Existing inverter paragraph."); // 4
            try (OutputStream out = Files.newOutputStream(blueprint)) {
                doc.write(out);
            }
        }
        roof = section(10, 0, "Roof", 1);
        inverter = section(20, 1, "Inverter", 3);
    }

    private static void text(XWPFDocument doc, String value) {
        doc.createParagraph().createRun().setText(value);
    }

    private static void heading(XWPFDocument doc, String value) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading1");
        p.createRun().setText(value);
    }

    private static DocBlueprintSection section(int id, int order, String title, int anchor) {
        DocBlueprintSection s = new DocBlueprintSection();
        s.setDocBlueprintSectionId(id);
        s.setOrderIndex(order);
        s.setTitle(title);
        s.setHeadingLevel(1);
        s.setAnchorParagraphIndex(anchor);
        s.setAnchorText(title);
        return s;
    }

    private static DocContentBlock paragraphBlock(int sectionId, int order, String text) {
        DocContentBlock b = new DocContentBlock();
        b.setSectionId(sectionId);
        b.setBlockType(DocContentBlock.TYPE_PARAGRAPH);
        b.setOrderIndex(order);
        b.setTextContent(text);
        return b;
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
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

    @Test
    void insertsBlocksInsideTheirSectionsKeepingEveryOriginalParagraph() throws IOException {
        Map<Integer, List<DocContentBlock>> blocks = Map.of(
                roof.getDocBlueprintSectionId(), List.of(
                        paragraphBlock(roof.getDocBlueprintSectionId(), 0, "Roof note one."),
                        paragraphBlock(roof.getDocBlueprintSectionId(), 1, "Roof note two.")),
                inverter.getDocBlueprintSectionId(), List.of(
                        paragraphBlock(inverter.getDocBlueprintSectionId(), 0, "Inverter note.")));

        byte[] result = assembler.assemble(blueprint, List.of(roof, inverter), blocks,
                Map.of(), id -> new byte[0]);

        assertThat(paragraphTexts(result)).containsExactly(
                "Company letterhead line.",
                "Roof",
                "Existing roof paragraph.",
                "Roof note one.",
                "Roof note two.",
                "Inverter",
                "Existing inverter paragraph.",
                "Inverter note.");
    }

    @Test
    void embedsImagesWithCaptionsScaledToThePage() throws IOException {
        byte[] hugePng = png(4000, 1000);
        DocImageAsset asset = new DocImageAsset();
        asset.setDocImageAssetId(7);
        asset.setOriginalFileName("roof_east.png");

        DocContentBlock image = new DocContentBlock();
        image.setSectionId(roof.getDocBlueprintSectionId());
        image.setBlockType(DocContentBlock.TYPE_IMAGE);
        image.setOrderIndex(0);
        image.setAssetId(7);
        image.setCaption("East roof face");

        byte[] result = assembler.assemble(blueprint, List.of(roof, inverter),
                Map.of(roof.getDocBlueprintSectionId(), List.of(image)),
                Map.of(7, asset), id -> hugePng);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(doc.getAllPictures()).hasSize(1);
            long width = doc.getParagraphs().stream()
                    .flatMap(p -> p.getRuns().stream())
                    .flatMap(r -> r.getEmbeddedPictures().stream())
                    .mapToLong(p -> p.getCTPicture().getSpPr().getXfrm().getExt().getCx())
                    .findFirst()
                    .orElseThrow();
            assertThat(width).isLessThanOrEqualTo(MAX_WIDTH_EMU);
        }
        // Caption lands between the image paragraph and the next heading.
        List<String> texts = paragraphTexts(result);
        assertThat(texts.indexOf("East roof face"))
                .isGreaterThan(texts.indexOf("Existing roof paragraph."))
                .isLessThan(texts.indexOf("Inverter"));
    }

    @Test
    void lastSectionContentAppendsAfterTheFinalOriginalParagraph() throws IOException {
        byte[] result = assembler.assemble(blueprint, List.of(roof, inverter),
                Map.of(inverter.getDocBlueprintSectionId(),
                        List.of(paragraphBlock(inverter.getDocBlueprintSectionId(), 0, "Very last note."))),
                Map.of(), id -> new byte[0]);

        List<String> texts = paragraphTexts(result);
        assertThat(texts.get(texts.size() - 1)).isEqualTo("Very last note.");
    }

    @Test
    void refusesToGenerateWhenTheAnchorNoLongerMatches() {
        roof.setAnchorText("A heading that is not there");

        assertThatThrownBy(() -> assembler.assemble(blueprint, List.of(roof, inverter),
                Map.of(), Map.of(), id -> new byte[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blueprint file changed");
    }

    @Test
    void implicitSectionAppendsAtTheEnd() throws IOException {
        DocBlueprintSection implicit = section(30, 0, "Whole document", -1);
        implicit.setHeadingLevel(0);
        implicit.setAnchorText(null);

        byte[] result = assembler.assemble(blueprint, List.of(implicit),
                Map.of(30, List.of(paragraphBlock(30, 0, "Appended."))),
                Map.of(), id -> new byte[0]);

        List<String> texts = paragraphTexts(result);
        assertThat(texts.get(texts.size() - 1)).isEqualTo("Appended.");
        assertThat(texts).contains("Company letterhead line.", "Roof", "Inverter");
    }

    @Test
    void multiLineParagraphTextBecomesLineBreaksNotLostText() throws IOException {
        byte[] result = assembler.assemble(blueprint, List.of(roof, inverter),
                Map.of(roof.getDocBlueprintSectionId(),
                        List.of(paragraphBlock(roof.getDocBlueprintSectionId(), 0, "line one\nline two"))),
                Map.of(), id -> new byte[0]);

        assertThat(paragraphTexts(result)).anyMatch(t -> t.contains("line one") && t.contains("line two"));
    }
}
