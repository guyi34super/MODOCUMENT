package com.modocument.doccreation.parse;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlueprintSectionParserTest {

    @TempDir
    Path tempDir;

    private final BlueprintSectionParser parser = new BlueprintSectionParser();

    private Path write(String name, Consumer<XWPFDocument> builder) throws IOException {
        Path path = tempDir.resolve(name);
        try (XWPFDocument doc = new XWPFDocument()) {
            builder.accept(doc);
            try (OutputStream out = Files.newOutputStream(path)) {
                doc.write(out);
            }
        }
        return path;
    }

    private static void paragraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.createRun().setText(text);
    }

    private static void heading(XWPFDocument doc, String text, String style) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle(style);
        p.createRun().setText(text);
    }

    @Test
    void splitsByHeadingStylesWithBodyElementAnchors() throws IOException {
        Path docx = write("styled.docx", doc -> {
            paragraph(doc, "Intro text before any heading.");       // 0
            heading(doc, "Roof Overview", "Heading1");              // 1
            paragraph(doc, "Roof body.");                           // 2
            doc.createTable(1, 1);                                  // 3 (a table between sections)
            heading(doc, "DC Routing", "Heading2");                 // 4
            paragraph(doc, "DC body.");                             // 5
        });

        List<ParsedSection> sections = parser.parse(docx, "fallback");

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("Roof Overview");
        assertThat(sections.get(0).headingLevel()).isEqualTo(1);
        assertThat(sections.get(0).anchorParagraphIndex()).isEqualTo(1);
        assertThat(sections.get(0).anchorText()).isEqualTo("Roof Overview");
        // The table occupies body-element index 3, so the second heading is 4.
        assertThat(sections.get(1).anchorParagraphIndex()).isEqualTo(4);
        assertThat(sections.get(1).headingLevel()).isEqualTo(2);
        assertThat(sections.get(1).orderIndex()).isEqualTo(1);
    }

    @Test
    void recognisesOutlineLevelWithoutAStyleName() throws IOException {
        Path docx = write("outline.docx", doc -> {
            XWPFParagraph p = doc.createParagraph();
            p.getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
            p.createRun().setText("Marked by outline level");
            paragraph(doc, "Body.");
        });

        List<ParsedSection> sections = parser.parse(docx, "fallback");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Marked by outline level");
        assertThat(sections.get(0).headingLevel()).isEqualTo(1);
    }

    @Test
    void headinglessDocumentBecomesOneImplicitSection() throws IOException {
        Path docx = write("plain.docx", doc -> {
            paragraph(doc, "Just some text.");
            paragraph(doc, "More text that is definitely not a heading because it is long and ends.");
        });

        List<ParsedSection> sections = parser.parse(docx, "Site Report");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Site Report");
        assertThat(sections.get(0).headingLevel()).isZero();
        assertThat(sections.get(0).anchorParagraphIndex()).isEqualTo(-1);
    }

    @Test
    void fallsBackToShortBoldParagraphsWhenNoStylesExist() throws IOException {
        Path docx = write("bold.docx", doc -> {
            XWPFParagraph h1 = doc.createParagraph();
            XWPFRun r1 = h1.createRun();
            r1.setBold(true);
            r1.setText("Roof");
            paragraph(doc, "Roof body text goes on and on.");
            XWPFParagraph h2 = doc.createParagraph();
            XWPFRun r2 = h2.createRun();
            r2.setBold(true);
            r2.setText("Inverter");
            paragraph(doc, "Inverter body.");
        });

        List<ParsedSection> sections = parser.parse(docx, "fallback");

        assertThat(sections).extracting(ParsedSection::title).containsExactly("Roof", "Inverter");
        assertThat(sections).allMatch(s -> s.headingLevel() == 1);
    }

    @Test
    void aSingleBoldLineIsNotTrustedAsAStructure() throws IOException {
        Path docx = write("onebold.docx", doc -> {
            XWPFParagraph h = doc.createParagraph();
            XWPFRun r = h.createRun();
            r.setBold(true);
            r.setText("Emphasis");
            paragraph(doc, "Body.");
        });

        List<ParsedSection> sections = parser.parse(docx, "Whole Doc");

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).headingLevel()).isZero();
    }

    @Test
    void seedsKeywordsFromTheTitleKeepingShortCodes() {
        assertThat(BlueprintSectionParser.seedKeywords("Roof Inspection – DC2 and the Report"))
                .containsExactly("roof", "inspection", "dc2");
    }

    @Test
    void rejectsAFileThatIsNotDocx() throws IOException {
        Path notDocx = tempDir.resolve("bad.docx");
        Files.writeString(notDocx, "not a zip");

        assertThatThrownBy(() -> parser.parse(notDocx, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a readable .docx");
    }
}
