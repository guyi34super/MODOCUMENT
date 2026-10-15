package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Filling templates built from the corpus: values come back out, nothing outside the regions changes. */
class PdfTemplateFillTest {

    private static final FillOptions VERIFY = new FillOptions(6f, true, true);
    private final DefaultPdfTemplateEngine engine = CorpusSupport.engine();

    static Stream<PdfCorpus.Doc> docs() {
        return PdfCorpus.all().stream();
    }

    private record Template(byte[] pdf, AnalysisResult analysis) {
    }

    private Template template(PdfCorpus.Doc doc) {
        AnalysisResult a = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
        byte[] pdf = engine.applyEdits(doc.filled(), CorpusSupport.applied(a), Map.of(), a.hasAcroForm());
        return new Template(pdf, a);
    }

    private static TemplateRegion region(AnalysisResult a, String key) {
        return a.regions().stream().filter(r -> r.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no region " + key + " in " + a.regions()));
    }

    private static String text(byte[] pdf) {
        return CorpusSupport.text(pdf);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void refilledValuesReadBackAndNothingElseChanges(PdfCorpus.Doc doc) {
        if (doc.needsOcr()) {
            Assumptions.assumeTrue(CorpusSupport.ocrAvailable(), "Tesseract is not installed here");
        }
        Template t = template(doc);
        Map<String, String> values = new HashMap<>();
        Map<String, byte[]> images = new HashMap<>();
        List<TemplateRegion> regions = CorpusSupport.accepted(t.analysis());
        for (TemplateRegion r : regions) {
            if (r.kind() == RegionKind.IMAGE || r.kind() == RegionKind.SIGNATURE) {
                images.put(r.key(), PdfCorpus.png(PdfCorpus.photo(60, 80)));
            } else if (r.kind() == RegionKind.CHECKBOX) {
                values.put(r.key(), "yes");
            } else if (r.sampleValue() != null) {
                values.put(r.key(), r.sampleValue());
            }
        }
        FillResult result = engine.fill(t.pdf(), regions, values, images, VERIFY);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().exact())
                .as("%s: %d differing pixels on %s", doc.name(), result.report().differingPixels(),
                        result.report().pagesWithDiffs())
                .isTrue();
        assertThat(result.addedPages()).isZero();
        String out = CorpusSupport.contentOrderText(result.pdf()).replaceAll("\\s+", " ");
        for (TemplateRegion r : regions) {
            if (PdfTemplateFiller.isText(r.kind()) && values.containsKey(r.key())) {
                for (String line : values.get(r.key()).split("\n")) {
                    assertThat(out).as("%s/%s", doc.name(), r.key()).contains(line.strip().replaceAll("\\s+", " "));
                }
            }
        }
    }

    @Test
    void newValuesReplaceTheSampleData() {
        PdfCorpus.Doc doc = PdfCorpus.letterhead();
        Template t = template(doc);
        Map<String, String> values = Map.of("ref", "ACM-2025-0999", "date", "1 April 2025", "to",
                "Zoë Ångström", "amount_due", "£980.00");
        FillResult r = engine.fill(t.pdf(), t.analysis().regions(), values, Map.of(), VERIFY);
        String out = text(r.pdf());
        assertThat(out).contains("Ref: ACM-2025-0999").contains("Date: 1 April 2025").contains("Zoë Ångström")
                .contains("£980.00").doesNotContain("Robert Brown").doesNotContain("ACM-2024-0117");
        assertThat(r.report().exact()).isTrue();
        // the region font is the template's own embedded subset when it has the glyphs, Liberation otherwise
        TemplateRegion to = region(t.analysis(), "to");
        assertThat(to.fontName()).endsWith("LiberationSerif");
    }

    @Test
    void longValueShrinksToFitItsBox() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.flatForm();
        Template t = template(doc);
        TemplateRegion dept = region(t.analysis(), "department");
        String value = "Finance, Accounting and Group Treasury";
        FillResult r = engine.fill(t.pdf(), t.analysis().regions(), Map.of(dept.key(), value), Map.of(), VERIFY);
        assertThat(r.continuedKeys()).isEmpty();
        assertThat(r.report().exact()).isTrue();
        assertThat(text(r.pdf())).contains(value);
        // drawn smaller than the region's own size
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            float size = 0;
            for (Glyph g : new ContentWalker(d.getPage(0), 0).walk().glyphs) {
                if (g.text.equals("T") && g.originY > dept.box().y() && g.originY < dept.box().y() + dept.box().h()) {
                    size = g.size;
                }
            }
            assertThat(size).isGreaterThanOrEqualTo(6f).isLessThan(dept.fontSize());
        }
    }

    @Test
    void textThatDoesNotFitContinuesOnAnAddedPageWithTheHeaderBand() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.letterhead();
        Template t = template(doc);
        TemplateRegion to = region(t.analysis(), "to");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append("word").append(i).append(' ');
        }
        String longText = sb.toString().strip();
        FillResult r = engine.fill(t.pdf(), t.analysis().regions(), Map.of(to.key(), longText), Map.of(), VERIFY);
        assertThat(r.continuedKeys()).containsExactly(to.key());
        assertThat(r.addedPages()).isGreaterThanOrEqualTo(1);
        assertThat(r.report().exact()).as("%d", r.report().differingPixels()).isTrue();
        try (PDDocument d = Loader.loadPDF(r.pdf()); PDDocument tpl = Loader.loadPDF(t.pdf())) {
            assertThat(d.getNumberOfPages()).isEqualTo(1 + r.addedPages());
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(1);
            s.setEndPage(1);
            assertThat(s.getText(d)).contains("(continued on page 2)");
            s.setStartPage(2);
            s.setEndPage(d.getNumberOfPages());
            String cont = s.getText(d).replaceAll("\\s+", " ");
            assertThat(cont).contains("Continued: To").contains("word0 word1").contains("word119");
            // the continuation page carries the template's letterhead band
            BufferedImage a = new PDFRenderer(tpl).renderImageWithDPI(0, 72, ImageType.RGB);
            BufferedImage b = new PDFRenderer(d).renderImageWithDPI(1, 72, ImageType.RGB);
            int band = (int) (a.getHeight() * PdfTemplateFiller.HEADER_BAND) - 2;
            long same = 0;
            long total = 0;
            long ink = 0;
            for (int y = 0; y < band; y++) {
                for (int x = 0; x < a.getWidth(); x++) {
                    total++;
                    int pa = a.getRGB(x, y) & 0xFFFFFF;
                    if (pa == (b.getRGB(x, y) & 0xFFFFFF)) {
                        same++;
                    }
                    if (pa != 0xFFFFFF) {
                        ink++;
                    }
                }
            }
            assertThat(ink).isGreaterThan(500);
            assertThat(same / (double) total).isGreaterThan(0.995);
        }
    }

    @Test
    void photoIsDrawnInsideItsBoxKeepingTheAspectRatio() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.flatForm();
        Template t = template(doc);
        TemplateRegion photo = region(t.analysis(), "photo");
        assertThat(photo.kind()).isEqualTo(RegionKind.IMAGE);
        byte[] png = PdfCorpus.png(PdfCorpus.photo(300, 200));
        FillResult r = engine.fill(t.pdf(), t.analysis().regions(), Map.of(), Map.of(photo.key(), png), VERIFY);
        assertThat(r.report().exact()).isTrue();
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            ContentWalker w = new ContentWalker(d.getPage(0), 0).walk();
            assertThat(w.images).hasSize(1);
            Rect placed = w.images.get(0).rect();
            Rect box = Rect.ofBox(photo.box());
            assertThat(box.containsRect(placed, 0.01f)).isTrue();
            assertThat(placed.w() / placed.h()).isCloseTo(1.5f, org.assertj.core.data.Offset.offset(0.01f));
            assertThat(placed.w()).isCloseTo(box.w(), org.assertj.core.data.Offset.offset(0.01f));
        }
    }

    @Test
    void checkboxGetsAMark() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.flatForm();
        Template t = template(doc);
        TemplateRegion box = region(t.analysis(), "part_time");
        assertThat(box.kind()).isEqualTo(RegionKind.CHECKBOX);
        FillResult on = engine.fill(t.pdf(), t.analysis().regions(), Map.of(box.key(), "x"), Map.of(), VERIFY);
        FillResult off = engine.fill(t.pdf(), t.analysis().regions(), Map.of(box.key(), "no"), Map.of(), VERIFY);
        assertThat(engine.verify(t.pdf(), on.pdf(), List.of(), 150).exact()).isFalse();
        assertThat(engine.verify(t.pdf(), off.pdf(), List.of(), 150).exact()).isTrue();
        assertThat(on.report().exact()).isTrue();
    }

    @Test
    void acroFormIsFilledAndFlattened() throws IOException {
        PdfCorpus.Doc doc = PdfCorpus.acroForm();
        Template t = template(doc);
        String address = t.analysis().regions().stream().filter(x -> "address".equals(x.acroFieldName()))
                .findFirst().orElseThrow().key();
        Map<String, String> values = Map.of("full_name", "Ana Lima", "email", "ana@example.org",
                address, "Rua Azul 5\nLisboa", "i_agree_to_the_terms", "true");
        FillResult r = engine.fill(t.pdf(), t.analysis().regions(), values, Map.of(), VERIFY);
        assertThat(r.report().exact()).as("%d", r.report().differingPixels()).isTrue();
        try (PDDocument d = Loader.loadPDF(r.pdf())) {
            var form = d.getDocumentCatalog().getAcroForm();
            assertThat(form == null || form.getFields().isEmpty()).isTrue();
            assertThat(d.getPage(0).getAnnotations()).isEmpty();
        }
        String out = text(r.pdf());
        assertThat(out).contains("Ana Lima").contains("ana@example.org").contains("Rua Azul 5").contains("Lisboa");
        // the template itself carries no values any more
        assertThat(text(t.pdf())).doesNotContain("Jane Doe");
        try (PDDocument d = Loader.loadPDF(t.pdf())) {
            assertThat(d.getDocumentCatalog().getAcroForm().getField("fullName").getValueAsString()).isEmpty();
        }
    }

    @Test
    void fillIsDeterministic() {
        PdfCorpus.Doc doc = PdfCorpus.tableForm();
        Template t = template(doc);
        Map<String, String> values = Map.of("description_1", "Pens", "qty_1", "4");
        byte[] a = engine.fill(t.pdf(), t.analysis().regions(), values, Map.of(), new FillOptions(6, true, false)).pdf();
        byte[] b = engine.fill(t.pdf(), t.analysis().regions(), values, Map.of(), new FillOptions(6, true, false)).pdf();
        assertThat(a).isEqualTo(b);
    }
}
