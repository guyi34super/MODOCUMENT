package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Refusals (contract §3) and the exactness check itself. */
class PdfTemplateLimitsTest {

    private final DefaultPdfTemplateEngine engine = CorpusSupport.engine();
    private final AnalysisOptions opts = CorpusSupport.codeOnly();

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (PdfTemplateException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void notAPdf() {
        byte[] docx = "PK\u0003\u0004word/document.xml".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(code(() -> engine.analyze(docx, UploadFlow.AUTO, opts))).isEqualTo("NOT_A_PDF");
        assertThat(code(() -> engine.pages(new byte[0]))).isEqualTo("NOT_A_PDF");
        assertThat(code(() -> engine.applyEdits(docx, List.of(), Map.of(), false))).isEqualTo("NOT_A_PDF");
    }

    @Test
    void unreadable() {
        byte[] junk = "%PDF-1.7\nthis is not a pdf body at all\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(code(() -> engine.analyze(junk, UploadFlow.AUTO, opts))).isEqualTo("UNREADABLE");
    }

    @Test
    void passwordProtected() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.protect(new StandardProtectionPolicy("owner", "user-secret", new AccessPermission()));
            pdf = save(doc);
        }
        assertThat(code(() -> engine.analyze(pdf, UploadFlow.AUTO, opts))).isEqualTo("ENCRYPTED");
    }

    @Test
    void encryptedWithAnEmptyUserPasswordIsAccepted() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 700, "Name: Ada Lovelace");
                PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 680, "Date: 1815-12-10");
            }
            AccessPermission ap = new AccessPermission();
            ap.setCanModify(false);
            doc.protect(new StandardProtectionPolicy("owner", "", ap));
            pdf = save(doc);
        }
        AnalysisResult r = engine.analyze(pdf, UploadFlow.WITH_DATA, opts);
        assertThat(r.removals()).hasSize(2);
        byte[] template = engine.applyEdits(pdf, r.removals(), Map.of(), false);
        try (PDDocument d = Loader.loadPDF(template)) {
            assertThat(d.isEncrypted()).isFalse();
        }
        assertThat(CorpusSupport.text(template)).contains("Name:").doesNotContain("Lovelace");
    }

    @Test
    void tooManyPages() throws IOException {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 501; i++) {
                doc.addPage(new PDPage(new PDRectangle(100, 100)));
            }
            pdf = save(doc);
        }
        assertThat(code(() -> engine.analyze(pdf, UploadFlow.BLANK, opts))).isEqualTo("TOO_MANY_PAGES");
    }

    @Test
    void verifyCountsDifferencesOutsideTheMaskOnly() {
        PdfCorpus.Doc doc = PdfCorpus.twoColumn();
        ExactnessReport same = engine.verify(doc.blank(), doc.blank(), List.of(), 150);
        assertThat(same.exact()).isTrue();
        assertThat(same.dpi()).isEqualTo(150);
        ExactnessReport diff = engine.verify(doc.blank(), doc.filled(), List.of(), 150);
        assertThat(diff.exact()).isFalse();
        assertThat(diff.pagesWithDiffs()).containsExactly(0);
        assertThat(diff.differingPixels()).isGreaterThan(100);
        // masking the value areas makes them equal again
        List<PdfBox> masks = doc.values().stream().map(PdfCorpus.Value::box)
                .map(b -> new PdfBox(b.page(), b.x() - 1, b.y() - 1, b.w() + 2, b.h() + 2)).toList();
        assertThat(engine.verify(doc.blank(), doc.filled(), masks, 150).exact()).isTrue();
    }

    @Test
    void verifyTreatsAMissingPageAsDifferentAndIgnoresExtraPages() throws IOException {
        byte[] one;
        byte[] two;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A6));
            one = save(doc);
            doc.addPage(new PDPage(PDRectangle.A6));
            two = save(doc);
        }
        assertThat(engine.verify(one, two, List.of(), 72).exact()).isTrue();
        ExactnessReport missing = engine.verify(two, one, List.of(), 72);
        assertThat(missing.exact()).isFalse();
        assertThat(missing.pagesWithDiffs()).containsExactly(1);
    }

    @Test
    void renderPageIsAPngCappedAt200Dpi() {
        PdfCorpus.Doc doc = PdfCorpus.mixedPages();
        byte[] png = engine.renderPage(doc.blank(), 2, 600);
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        java.awt.image.BufferedImage img;
        try {
            img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // page 3 is A4 portrait with /Rotate 90: shown landscape, at 200 dpi
        assertThat(img.getWidth()).isEqualTo((int) Math.floor(841.89f * 200 / 72));
        assertThat(img.getHeight()).isEqualTo((int) Math.floor(595.28f * 200 / 72));
    }
}
