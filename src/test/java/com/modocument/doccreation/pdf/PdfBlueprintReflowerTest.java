package com.modocument.doccreation.pdf;

import com.modocument.infrastructure.file.OcrService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfBlueprintReflowerTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @TempDir
    Path tmp;

    private final OcrService ocr = mock(OcrService.class);
    private final PdfBlueprintReflower reflower = new PdfBlueprintReflower(ocr);

    @Test
    void largerFontsAndNumberedLinesBecomeHeadingParagraphs() throws IOException {
        Path pdf = write(doc -> page(doc, s -> {
            text(s, BOLD, 20, 50, 740, "Unsecured Loan Application");
            text(s, REGULAR, 10, 50, 700, "Please complete every part of this form in block capitals and sign");
            text(s, REGULAR, 10, 50, 688, "at the end. Incomplete forms will be returned to the applicant.");
            text(s, BOLD, 12, 50, 650, "1. Applicant Details");
            text(s, REGULAR, 10, 50, 630, "The applicant is the person who will be responsible for repaying");
            text(s, REGULAR, 10, 50, 618, "the loan and all related charges under the agreement.");
            text(s, BOLD, 12, 50, 580, "2. Employment and Income");
            text(s, REGULAR, 10, 50, 560, "Give details of your current employer and your monthly income");
            text(s, REGULAR, 10, 50, 548, "before deductions, together with any other regular income.");
        }));

        PdfBlueprintReflower.Reflowed result = reflower.toDocx(pdf);

        assertThat(headings(result)).containsExactly(
                "Unsecured Loan Application", "1. Applicant Details", "2. Employment and Income");
        assertThat(texts(result)).contains("Please complete every part of this form in block capitals and sign "
                + "at the end. Incomplete forms will be returned to the applicant.");
        assertThat(result.pages()).isEqualTo(1);
        assertThat(result.ocrPages()).isZero();
        verify(ocr, never()).extractText(any());
    }

    @Test
    void filledFormFieldValuesAreMergedIntoTheText() throws IOException {
        Path pdf = write(doc -> {
            PDPage page = page(doc, s -> {
                text(s, BOLD, 14, 50, 740, "PERSONAL DETAILS");
                text(s, REGULAR, 10, 50, 700, "Full name of applicant");
                text(s, REGULAR, 10, 50, 670, "This section must be completed by every applicant without exception.");
            });
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), REGULAR);
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 10 Tf 0 g");
            PDTextField field = new PDTextField(form);
            field.setPartialName("fullName");
            PDAnnotationWidget widget = field.getWidgets().get(0);
            widget.setRectangle(new PDRectangle(250, 696, 200, 14));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            form.getFields().add(field);
            field.setValue("Jane Perera");
        });

        String all = String.join("\n", texts(reflower.toDocx(pdf)));

        assertThat(all).contains("Jane Perera");
        assertThat(all).contains("Full name of applicant");
    }

    @Test
    void aScannedPageIsReadWithOcr() throws IOException {
        Path pdf = write(doc -> page(doc, s -> {
        }));
        when(ocr.extractText(any(BufferedImage.class)))
                .thenReturn("SECTION A PERSONAL DETAILS\n\nName of the applicant as shown on the identity card\n");

        PdfBlueprintReflower.Reflowed result = reflower.toDocx(pdf);

        assertThat(result.ocrPages()).isEqualTo(1);
        assertThat(texts(result)).contains("SECTION A PERSONAL DETAILS",
                "Name of the applicant as shown on the identity card");
        assertThat(headings(result)).contains("SECTION A PERSONAL DETAILS");
    }

    @Test
    void aPdfWithNoTextAndNoOcrIsEmptySoTheCallerCanFallBack() throws IOException {
        Path pdf = write(doc -> page(doc, s -> {
        }));
        when(ocr.extractText(any(BufferedImage.class))).thenReturn("");

        assertThat(reflower.toDocx(pdf).isEmpty()).isTrue();
    }

    @Test
    void aPasswordProtectedPdfIsRefused() throws IOException {
        Path pdf = tmp.resolve("locked.pdf");
        try (PDDocument doc = new PDDocument()) {
            page(doc, s -> text(s, REGULAR, 10, 50, 700, "Secret"));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(pdf.toFile());
        }

        assertThatThrownBy(() -> reflower.toDocx(pdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void runningHeadersAndPageNumbersAreDropped() throws IOException {
        Path pdf = write(doc -> {
            for (int i = 1; i <= 3; i++) {
                int pageNo = i;
                page(doc, s -> {
                    text(s, REGULAR, 8, 50, 770, "ACME Bank PLC - Confidential");
                    text(s, REGULAR, 10, 50, 600, "Body text of page " + pageNo
                            + " describing the terms and conditions of the facility in detail.");
                    text(s, REGULAR, 8, 300, 30, String.valueOf(pageNo));
                });
            }
        });

        List<String> texts = texts(reflower.toDocx(pdf));

        assertThat(texts).noneMatch(t -> t.contains("ACME Bank PLC"));
        assertThat(texts).noneMatch(t -> t.matches("\\d"));
        assertThat(texts).anyMatch(t -> t.startsWith("Body text of page 2"));
    }

    @Test
    void twoColumnPagesAreReadColumnByColumn() throws IOException {
        Path pdf = write(doc -> page(doc, s -> {
            for (int i = 1; i <= 8; i++) {
                float y = 700 - i * 40;
                text(s, REGULAR, 10, 40, y, "Left column paragraph " + i + " keeps on going here.");
                text(s, REGULAR, 10, 330, y, "Right column paragraph " + i + " keeps on going too.");
            }
        }));

        String all = String.join("\n", texts(reflower.toDocx(pdf)));

        assertThat(all.indexOf("Left column paragraph 8")).isLessThan(all.indexOf("Right column paragraph 1"));
    }

    @Test
    void bookmarksMarkHeadingsEvenInBodySizedText() throws IOException {
        Path pdf = write(doc -> {
            PDPage page = page(doc, s -> {
                text(s, REGULAR, 10, 50, 740, "Terms and conditions");
                text(s, REGULAR, 10, 50, 720, "These terms apply to every facility granted by the bank to the borrower.");
                text(s, REGULAR, 10, 50, 680, "Repayment schedule");
                text(s, REGULAR, 10, 50, 660, "Instalments are payable monthly on the date agreed in the offer letter.");
            });
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            for (String title : List.of("Terms and conditions", "Repayment schedule")) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(title);
                item.setDestination(page);
                outline.addLast(item);
            }
        });

        assertThat(headings(reflower.toDocx(pdf))).containsExactly("Terms and conditions", "Repayment schedule");
    }

    @Test
    void wideGapsInALineBecomeTabSeparatedCells() throws IOException {
        Path pdf = write(doc -> page(doc, s -> {
            text(s, REGULAR, 10, 50, 700, "Loan amount requested");
            text(s, REGULAR, 10, 350, 700, "LKR 1,500,000");
            text(s, REGULAR, 10, 50, 660, "The bank may approve a lower amount than the amount requested above.");
        }));

        PdfBlueprintReflower.Reflowed result = reflower.toDocx(pdf);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result.docx()))) {
            XWPFParagraph row = doc.getParagraphs().stream()
                    .filter(p -> p.getText().contains("Loan amount requested")).findFirst().orElseThrow();
            assertThat(row.getText()).isEqualTo("Loan amount requested\tLKR 1,500,000");
        }
    }

    @Test
    void letterBoxValuesAreGluedBackIntoWords() throws IOException {
        Path pdf = write(doc -> page(doc, s -> {
            text(s, REGULAR, 10, 50, 720, "Name of the applicant as on the identity card");
            float x = 50;
            for (char c : "ARJUN RAJESH".toCharArray()) {
                if (c != ' ') {
                    text(s, REGULAR, 8, x, 700, String.valueOf(c));
                }
                x += 12.5f;
            }
        }));

        assertThat(texts(reflower.toDocx(pdf))).contains("ARJUN RAJESH");
    }

    @Test
    void aFormPrintedAsAnImageIsReadWithOcrEvenWhenValuesAreText() throws IOException {
        Path pdf = write(doc -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            BufferedImage scan = new BufferedImage(600, 840, BufferedImage.TYPE_INT_RGB);
            var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, scan);
            try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                s.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                text(s, REGULAR, 9, 120, 700, "Mehta Textiles Private Limited");
            }
        });
        when(ocr.extractText(any(BufferedImage.class))).thenReturn(
                "SECTION 1 - BUSINESS DETAILS\nName of the business Mehta Textiles Private Limited\n"
                        + "Nature of business and the year it was established\n");

        PdfBlueprintReflower.Reflowed result = reflower.toDocx(pdf);

        assertThat(result.ocrPages()).isEqualTo(1);
        assertThat(texts(result)).contains("SECTION 1 - BUSINESS DETAILS");
        assertThat(headings(result)).contains("SECTION 1 - BUSINESS DETAILS");
    }

    @Test
    void aTextPdfWithoutImagesIsNeverOcrd() throws IOException {
        Path pdf = write(doc -> page(doc, s ->
                text(s, REGULAR, 10, 50, 700, "Plain text page with enough characters to count as text.")));

        reflower.toDocx(pdf);

        verify(ocr, never()).extractText(any());
    }

    @Test
    void textualCuesGiveHeadingLevels() {
        assertThat(cue("3.1 Earthing arrangements")).isEqualTo(2);
        assertThat(cue("Section 4 Security")).isEqualTo(1);
        assertThat(cue("Schedule A")).isEqualTo(1);
        assertThat(cue("IV. Declarations")).isEqualTo(1);
        assertThat(cue("(b) Joint applicant")).isEqualTo(3);
        assertThat(cue("12.05.2024")).isZero();
        assertThat(cue("Section of the form that follows is optional.")).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private static int cue(String text) {
        PdfBlueprintReflower.Line line = new PdfBlueprintReflower.Line(0, List.of(text), 100, 10, 10, false, false);
        return PdfBlueprintReflower.textualCueLevel(line, null, 1);
    }

    interface PageWriter {
        void write(PDPageContentStream stream) throws IOException;
    }

    interface DocWriter {
        void write(PDDocument doc) throws IOException;
    }

    private Path write(DocWriter writer) throws IOException {
        Path pdf = tmp.resolve("blueprint-" + System.nanoTime() + ".pdf");
        try (PDDocument doc = new PDDocument()) {
            writer.write(doc);
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    private static PDPage page(PDDocument doc, PageWriter writer) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
            writer.write(stream);
        }
        return page;
    }

    private static void text(PDPageContentStream s, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        s.beginText();
        s.setFont(font, size);
        s.newLineAtOffset(x, y);
        s.showText(text);
        s.endText();
    }

    private static List<String> texts(PdfBlueprintReflower.Reflowed result) throws IOException {
        List<String> out = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result.docx()))) {
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                out.add(paragraph.getText());
            }
        }
        return out;
    }

    private static List<String> headings(PdfBlueprintReflower.Reflowed result) throws IOException {
        List<String> out = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result.docx()))) {
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                var ppr = paragraph.getCTP().getPPr();
                if (ppr != null && ppr.isSetOutlineLvl()) {
                    out.add(paragraph.getText());
                }
            }
        }
        return out;
    }
}
