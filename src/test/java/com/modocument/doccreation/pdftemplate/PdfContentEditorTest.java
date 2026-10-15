package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The content-stream editor on hand-written content: removed glyphs vanish, everything else stays put. */
class PdfContentEditorTest {

    private final DefaultPdfTemplateEngine engine = CorpusSupport.engine();

    /** Builds a one-page PDF from raw content; the font builder registers fonts as /F1, /F2. */
    private static byte[] raw(String content, Function<PDDocument, Map<String, PDFont>> fonts) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDResources res = new PDResources();
            for (Map.Entry<String, PDFont> e : fonts.apply(doc).entrySet()) {
                res.put(COSName.getPDFName(e.getKey()), e.getValue());
            }
            page.setResources(res);
            PDStream s = new PDStream(doc);
            try (OutputStream os = s.createOutputStream(COSName.FLATE_DECODE)) {
                os.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(s);
            return save(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        doc.setDocumentId(7L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static Map<String, PDFont> helv(PDDocument doc) {
        return Map.of("F1", PdfCorpus.helv());
    }

    private static TemplateEdit remove(int page, float x, float y, float w, float h) {
        return new TemplateEdit("e", EditKind.REMOVE, new PdfBox(page, x, y, w, h), null, null, null, null, null,
                EditSource.USER, 1, null);
    }

    private static String text(byte[] pdf, int page) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page + 1);
            s.setEndPage(page + 1);
            return s.getText(d).strip();
        }
    }

    @Test
    void tjWithKerningKeepsTheRemainingGlyphsInPlace() throws IOException {
        byte[] filled = raw("BT /F1 12 Tf 72 700 Td [(Na) -20 (me: J) 30 (ohn) -15 ( Smith) 40 (, Esq)] TJ ET", PdfContentEditorTest::helv);
        byte[] blank = raw("BT /F1 12 Tf 72 700 Td [(Na) -20 (me:)] TJ ET", PdfContentEditorTest::helv);
        // "John Smith, Esq" starts after "Name: " (about 108 pt) on the 700 baseline
        TemplateEdit cut = remove(0, 108, 696, 120, 14);
        byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
        assertThat(text(out, 0)).isEqualTo("Name:");
        ExactnessReport r = engine.verify(blank, out, List.of(cut.box()), 150);
        assertThat(r.exact()).as("%d pixels", r.differingPixels()).isTrue();
        // and the cut is real: without the mask, the filled page differs from the blank twin
        assertThat(engine.verify(blank, filled, List.of(), 150).exact()).isFalse();
    }

    /** The page's content tokens (operands and operators) as PDFBox parses them. */
    private static List<Object> contentTokens(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFStreamParser(d.getPage(0)).parse();
        }
    }

    private static boolean hasNumbers(Object token) {
        if (!(token instanceof COSArray array)) {
            return false;
        }
        for (int i = 0; i < array.size(); i++) {
            if (array.getObject(i) instanceof COSNumber) {
                return true;
            }
        }
        return false;
    }

    @Test
    void removedValuesLeaveNoPerGlyphWidthsBehind() throws IOException {
        // BUG-PDF-02: a removed value used to stay in the template as a TJ of its glyph advances
        // ([-500 -556 -556 -556 -278 -667 ...] for "John Smith"): its length, word breaks, '@' and
        // digit groups survive the removal. A value drawn on its own, or at the end of a label run,
        // has no glyph after it that needs the advance, so nothing of it may be left.
        byte[] filled = raw("BT /F1 10 Tf 72 700 Td (John Smith) Tj ET "
                + "BT /F1 10 Tf 72 680 Td (Email: jane@example.com) Tj ET", PdfContentEditorTest::helv);
        byte[] blank = raw("BT /F1 10 Tf 72 680 Td (Email:) Tj ET", PdfContentEditorTest::helv);
        float label = PdfCorpus.helv().getStringWidth("Email: ") / 1000f * 10;
        TemplateEdit name = remove(0, 70, 696, 80, 14);
        TemplateEdit mail = remove(0, 72 + label - 1, 676, 120, 14);
        byte[] out = engine.applyEdits(filled, List.of(name, mail), Map.of(), false);

        assertThat(text(out, 0)).isEqualTo("Email:");
        ExactnessReport r = engine.verify(blank, out, List.of(), 150);
        assertThat(r.exact()).as("%d pixels", r.differingPixels()).isTrue();
        assertThat(contentTokens(out)).as("TJ arrays carrying advances of removed glyphs")
                .noneMatch(PdfContentEditorTest::hasNumbers);
    }

    @Test
    void anAdvanceAFollowingStringDependsOnIsKept() throws IOException {
        // "(John) Tj ( Smith) Tj": the second string starts where the first one ended, so the removed
        // first string's advance must stay (as a displacement), or " Smith" would move.
        byte[] filled = raw("BT /F1 10 Tf 72 700 Td (John) Tj ( Smith) Tj ET", PdfContentEditorTest::helv);
        float john = PdfCorpus.helv().getStringWidth("John") / 1000f * 10;
        TemplateEdit cut = remove(0, 71, 696, john + 0.5f, 14);
        byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
        assertThat(text(out, 0)).isEqualTo("Smith");
        ExactnessReport r = engine.verify(filled, out, List.of(cut.box()), 150);
        assertThat(r.exact()).as("%d pixels", r.differingPixels()).isTrue();
    }

    @Test
    void glyphsAfterTheCutKeepTheirExactPosition() throws IOException {
        // the removed word sits between two kept words of one string
        byte[] filled = raw("BT /F1 11 Tf 72 700 Td (Left MIDDLE Right) Tj ET", PdfContentEditorTest::helv);
        PDFont f = PdfCorpus.helv();
        float left = f.getStringWidth("Left ") / 1000f * 11;
        float mid = f.getStringWidth("MIDDLE") / 1000f * 11;
        TemplateEdit cut = remove(0, 72 + left + 0.5f, 696, mid - 1, 14);
        byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
        assertThat(text(out, 0)).contains("Left").contains("Right").doesNotContain("MIDDLE");
        ExactnessReport r = engine.verify(filled, out, List.of(cut.box()), 150);
        assertThat(r.exact()).as("%d pixels", r.differingPixels()).isTrue();
        // "Right" is drawn at the very same position as before
        try (PDDocument a = Loader.loadPDF(filled); PDDocument b = Loader.loadPDF(out)) {
            List<Glyph> before = new ContentWalker(a.getPage(0), 0).walk().glyphs;
            List<Glyph> after = new ContentWalker(b.getPage(0), 0).walk().glyphs;
            Glyph r0 = before.stream().filter(g -> g.text.equals("R")).findFirst().orElseThrow();
            Glyph r1 = after.stream().filter(g -> g.text.equals("R")).findFirst().orElseThrow();
            assertThat(r1.originX).isEqualTo(r0.originX);
            assertThat(r1.originY).isEqualTo(r0.originY);
        }
    }

    @Test
    void cidFontGlyphsAreSplitByTwoByteCodes() throws IOException {
        byte[] filled = build(doc -> {
            PDFont f = PdfCorpus.liberation(doc, "LiberationSans");
            return page(doc, cs -> {
                cs.beginText();
                cs.setFont(f, 14);
                cs.newLineAtOffset(72, 700);
                cs.showTextWithPositioning(new Object[] {"Όνομα:", -30f, " Γιώργος", 15f, " Νίκου"});
                cs.endText();
            });
        });
        byte[] blank = build(doc -> {
            PDFont f = PdfCorpus.liberation(doc, "LiberationSans");
            return page(doc, cs -> {
                cs.beginText();
                cs.setFont(f, 14);
                cs.newLineAtOffset(72, 700);
                cs.showTextWithPositioning(new Object[] {"Όνομα:"});
                cs.endText();
            });
        });
        float labelEnd;
        try (PDDocument d = new PDDocument()) {
            PDFont f = PdfCorpus.liberation(d, "LiberationSans");
            labelEnd = 72 + f.getStringWidth("Όνομα:") / 1000f * 14 + 0.42f;
        }
        TemplateEdit cut = remove(0, labelEnd + 1, 695, 200, 18);
        byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
        assertThat(text(out, 0)).isEqualTo("Όνομα:");
        ExactnessReport r = engine.verify(blank, out, List.of(cut.box()), 150);
        assertThat(r.exact()).as("%d pixels", r.differingPixels()).isTrue();
    }

    @Test
    void sharedFormXObjectIsCopiedBeforeItIsEdited() throws IOException {
        byte[] pdf = build(doc -> {
            PDFormXObject form = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(
                    doc.getDocument().createCOSStream());
            form.setBBox(new PDRectangle(0, 0, 400, 60));
            form.setResources(new PDResources());
            try (PDPageContentStream fs = new PDPageContentStream(doc,
                    (org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream) form)) {
                PdfCorpus.text(fs, PdfCorpus.helv(), 10, 10, 30, "Reference: ABC-12345");
            }
            for (int i = 0; i < 2; i++) {
                PDPage p = new PDPage(PDRectangle.A4);
                doc.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                    cs.saveGraphicsState();
                    cs.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(62, 670));
                    cs.drawForm(form);
                    cs.restoreGraphicsState();
                }
            }
            return doc;
        });
        // "ABC-12345" starts at x 72 + w("Reference: ") = 121.7 on baseline 700 of both pages; cut page 1 only
        TemplateEdit cut = remove(0, 120.5f, 696, 80, 14);
        byte[] out = engine.applyEdits(pdf, List.of(cut), Map.of(), false);
        assertThat(text(out, 0)).isEqualTo("Reference:");
        assertThat(text(out, 1)).isEqualTo("Reference: ABC-12345");
        ExactnessReport r = engine.verify(pdf, out, List.of(cut.box()), 150);
        assertThat(r.exact()).as("%d pixels on %s", r.differingPixels(), r.pagesWithDiffs()).isTrue();
    }

    @Test
    void nestedSaveRestoreAndMatricesAreTracked() throws IOException {
        String content = "q 1 0 0 1 50 40 cm q 1.5 0 0 1.5 0 0 cm BT /F1 10 Tf 20 400 Td (Ref: 9981) Tj ET Q "
                + "BT /F1 10 Tf 20 500 Td (Kept: 9981) Tj ET Q BT /F1 10 Tf 72 300 Td (Also kept 9981) Tj ET";
        byte[] filled = raw(content, PdfContentEditorTest::helv);
        // the scaled "9981" is at x = 50 + 1.5 * (20 + w("Ref: ")) and baseline 40 + 600 = 640
        PDFont f = PdfCorpus.helv();
        float x = 50 + 1.5f * (20 + f.getStringWidth("Ref: ") / 1000f * 10);
        TemplateEdit cut = remove(0, x - 1, 636, 40, 18);
        byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
        String t = text(out, 0);
        assertThat(t).contains("Ref:").contains("Kept: 9981").contains("Also kept 9981");
        assertThat(t.replace("Kept: 9981", "").replace("Also kept 9981", "")).doesNotContain("9981");
        assertThat(engine.verify(filled, out, List.of(cut.box()), 150).exact()).isTrue();
    }

    @Test
    void quoteOperatorsAreRewrittenWithTheirLineMoves() throws IOException {
        String content = "BT /F1 10 Tf 14 TL 72 700 Td (Name:) Tj (Value one) ' 2 0 (Value two) \" (Tail) ' ET";
        byte[] filled = raw(content, PdfContentEditorTest::helv);
        // "Value one" is on the second line (baseline 686)
        TemplateEdit cut = remove(0, 70, 683, 60, 12);
        byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
        String t = text(out, 0);
        assertThat(t).contains("Name:").contains("Value two").contains("Tail").doesNotContain("Value one");
        assertThat(engine.verify(filled, out, List.of(cut.box()), 150).exact()).isTrue();
    }

    @Test
    void characterAndWordSpacingAreKept() throws IOException {
        String content = "BT /F1 10 Tf 0.7 Tc 2.5 Tw 1.3 Ts 90 Tz 72 700 Td (Code: X17 Q AND THE REST) Tj ET";
        byte[] filled = raw(content, PdfContentEditorTest::helv);
        try (PDDocument d = Loader.loadPDF(filled)) {
            ContentWalker w = new ContentWalker(d.getPage(0), 0).walk();
            // cut "X17 Q" (glyphs 6..10)
            Rect a = w.glyphs.get(6).ink;
            Rect b = w.glyphs.get(10).ink;
            TemplateEdit cut = remove(0, a.x0() - 0.5f, 698, b.x1() - a.x0() + 1, 12);
            byte[] out = engine.applyEdits(filled, List.of(cut), Map.of(), false);
            assertThat(text(out, 0)).doesNotContain("X17").contains("REST");
            ExactnessReport r = engine.verify(filled, out, List.of(cut.box()), 150);
            assertThat(r.exact()).as("%d pixels", r.differingPixels()).isTrue();
        }
    }

    @Test
    void scannedAreaIsPaintedWithTheSurroundingColour() throws IOException {
        BufferedImage scan = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scan.createGraphics();
        g.setColor(new Color(250, 240, 200));
        g.fillRect(0, 0, 400, 200);
        g.setColor(Color.BLACK);
        g.fillRect(150, 90, 60, 20);
        g.dispose();
        byte[] pdf = build(doc -> page(doc, cs -> {
            PDImageXObject img = LosslessFactory.createFromImage(doc, scan);
            cs.drawImage(img, 100, 500, 400, 200);
        }));
        // the black block is at image (150..210, 90..110) -> page x 250..310, y 590..610
        TemplateEdit cut = remove(0, 245, 585, 70, 30);
        byte[] out = engine.applyEdits(pdf, List.of(cut), Map.of(), false);
        try (PDDocument d = Loader.loadPDF(out)) {
            BufferedImage img = new PDFRenderer(d).renderImageWithDPI(0, 72, ImageType.RGB);
            int rgb = img.getRGB(280, 842 - 600) & 0xFFFFFF;
            assertThat(rgb).isEqualTo(0xFAF0C8);
        }
        assertThat(engine.verify(pdf, out, List.of(cut.box()), 150).exact()).isTrue();
    }

    @Test
    void imageInsideTheBoxIsCutFromTheContent() throws IOException {
        byte[] pdf = build(doc -> page(doc, cs -> {
            PDImageXObject img = LosslessFactory.createFromImage(doc, PdfCorpus.photo(40, 50));
            cs.drawImage(img, 300, 600, 40, 50);
            PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 700, "Photo");
        }));
        TemplateEdit cut = remove(0, 295, 595, 50, 60);
        byte[] out = engine.applyEdits(pdf, List.of(cut), Map.of(), false);
        try (PDDocument d = Loader.loadPDF(out)) {
            ContentWalker w = new ContentWalker(d.getPage(0), 0).walk();
            assertThat(w.images).isEmpty();
            assertThat(w.glyphs).isNotEmpty();
        }
    }

    @Test
    void replaceTextKeepsBaselineFontAndColour() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.letterhead();
        // "Yours sincerely," at (72, 540) in the embedded Liberation Serif subset
        TemplateEdit edit = new TemplateEdit("r1", EditKind.REPLACE_TEXT, new PdfBox(0, 70, 536, 90, 14),
                "Yours truly,", null, null, null, null, EditSource.USER, 1, null);
        byte[] out = engine.applyEdits(doc.blank(), List.of(edit), Map.of(), false);
        assertThat(CorpusSupport.text(out)).contains("Yours truly,").doesNotContain("sincerely");
        try (PDDocument d = Loader.loadPDF(out)) {
            Glyph y = new ContentWalker(d.getPage(0), 0).walk().glyphs.stream()
                    .filter(g -> g.text.equals("Y") && g.originY < 545 && g.originY > 535).findFirst().orElseThrow();
            assertThat(y.originX).isEqualTo(72f);
            assertThat(y.originY).isEqualTo(540f);
            assertThat(y.size).isEqualTo(11f);
            assertThat(y.fontName).endsWith("LiberationSerif");
        }
        // the rest of the page is untouched (the new text is a little narrower than the old one)
        assertThat(engine.verify(doc.blank(), out, List.of(edit.box()), 150).exact()).isTrue();
    }

    @Test
    void addTextAndAddImageDrawInsideTheirBoxes() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.twoColumn();
        PdfBox textBox = new PdfBox(0, 300, 50, 200, 30);
        PdfBox logoBox = new PdfBox(0, 450, 780, 80, 40);
        List<TemplateEdit> edits = List.of(
                new TemplateEdit("a1", EditKind.ADD_TEXT, textBox, "Confidential – internal use", "LiberationSans-Bold",
                        12f, "#C00000", null, EditSource.USER, 1, null),
                new TemplateEdit("a2", EditKind.ADD_IMAGE, logoBox, null, null, null, null, "logo", EditSource.USER, 1,
                        null));
        byte[] out = engine.applyEdits(doc.blank(), edits, Map.of("logo", PdfCorpus.png(PdfCorpus.logo(64))), false);
        assertThat(CorpusSupport.text(out)).contains("Confidential – internal use");
        assertThat(engine.verify(doc.blank(), out, List.of(textBox, logoBox), 150).exact()).isTrue();
        assertThat(engine.verify(doc.blank(), out, List.of(), 150).exact()).isFalse();
        try (PDDocument d = Loader.loadPDF(out)) {
            ContentWalker w = new ContentWalker(d.getPage(0), 0).walk();
            assertThat(w.images).hasSize(1);
            assertThat(Rect.ofBox(logoBox).containsRect(w.images.get(0).rect(), 0.01f)).isTrue();
            Glyph c = w.glyphs.stream().filter(g -> g.text.equals("C")).findFirst().orElseThrow();
            assertThat(c.rgb).isEqualTo(0xC00000);
            assertThat(Rect.ofBox(textBox).containsRect(c.box, 0.5f)).isTrue();
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface DocBuilder {
        PDDocument build(PDDocument doc) throws IOException;
    }

    private interface Drawing {
        void draw(PDPageContentStream cs) throws IOException;
    }

    private static byte[] build(DocBuilder b) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            b.build(doc);
            return save(doc);
        }
    }

    private static PDDocument page(PDDocument doc, Drawing d) throws IOException {
        PDPage p = new PDPage(PDRectangle.A4);
        doc.addPage(p);
        try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
            d.draw(cs);
        }
        return doc;
    }
}
