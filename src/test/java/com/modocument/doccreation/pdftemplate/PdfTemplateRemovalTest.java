package com.modocument.doccreation.pdftemplate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removal exactness on the corpus: cutting the detected data out of the FILLED twin gives the BLANK twin,
 * pixel for pixel outside the removal boxes (150 DPI), with the static text intact and the data gone.
 */
class PdfTemplateRemovalTest {

    static Stream<PdfCorpus.Doc> docs() {
        return PdfCorpus.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void removingDetectedDataGivesTheBlankTwin(PdfCorpus.Doc doc) {
        if (doc.needsOcr()) {
            Assumptions.assumeTrue(CorpusSupport.ocrAvailable(), "Tesseract is not installed here");
        }
        DefaultPdfTemplateEngine engine = CorpusSupport.engine();
        AnalysisResult analysis = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
        List<TemplateEdit> removals = CorpusSupport.applied(analysis);
        byte[] template = engine.applyEdits(doc.filled(), removals, Map.of(), analysis.hasAcroForm());

        List<PdfBox> allowed = new ArrayList<>(CorpusSupport.boxes(removals));
        for (TemplateRegion r : analysis.regions()) {
            if (r.acroFieldName() != null) {
                allowed.add(r.box());
            }
        }
        ExactnessReport report = engine.verify(doc.blank(), template, allowed, 150);
        assertThat(report.exact())
                .as("%s: %d differing pixels on pages %s", doc.name(), report.differingPixels(), report.pagesWithDiffs())
                .isTrue();

        if (!doc.needsOcr()) {
            // same words as the blank twin: static text intact, data gone
            assertThat(CorpusSupport.words(template)).as(doc.name()).isEqualTo(CorpusSupport.words(doc.blank()));
        }
        String text = CorpusSupport.text(template);
        for (PdfCorpus.Value v : doc.values()) {
            if (!v.text().startsWith("[") && !"true".equals(v.text()) && v.text().length() > 2) {
                assertThat(text).as(doc.name()).doesNotContain(v.text().split("\n")[0]);
            }
        }
    }

    /** BUG-PDF-01: every proposed removal names the DATA_REMOVED (or AI) region that takes its value's place. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void everyRemovalIsLinkedToItsOwnDataRegion(PdfCorpus.Doc doc) {
        if (doc.needsOcr()) {
            Assumptions.assumeTrue(CorpusSupport.ocrAvailable(), "Tesseract is not installed here");
        }
        AnalysisResult analysis = CorpusSupport.engine().analyze(doc.filled(), UploadFlow.WITH_DATA,
                CorpusSupport.codeOnly());
        Map<String, TemplateRegion> byKey = new java.util.HashMap<>();
        analysis.regions().forEach(r -> byKey.put(r.key(), r));
        java.util.Set<String> linked = new java.util.HashSet<>();
        for (TemplateEdit removal : analysis.removals()) {
            assertThat(removal.regionKey()).as("%s: %s", doc.name(), removal.removedText()).isNotNull();
            TemplateRegion region = byKey.get(removal.regionKey());
            assertThat(region).as("%s: region %s", doc.name(), removal.regionKey()).isNotNull();
            assertThat(region.source()).isIn(RegionSource.DATA_REMOVED, RegionSource.AI);
            assertThat(region.box().page()).isEqualTo(removal.box().page());
            assertThat(region.sampleValue()).isEqualTo(removal.removedText());
            assertThat(linked.add(removal.regionKey())).as("one removal per region").isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void blankFlowKeepsTheOriginalBytes(PdfCorpus.Doc doc) {
        DefaultPdfTemplateEngine engine = CorpusSupport.engine();
        byte[] template = engine.applyEdits(doc.blank(), List.of(), Map.of(), false);
        assertThat(template).isSameAs(doc.blank());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void applyEditsIsDeterministic(PdfCorpus.Doc doc) {
        if (doc.needsOcr()) {
            Assumptions.assumeTrue(CorpusSupport.ocrAvailable(), "Tesseract is not installed here");
        }
        DefaultPdfTemplateEngine engine = CorpusSupport.engine();
        AnalysisResult analysis = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
        List<TemplateEdit> removals = CorpusSupport.applied(analysis);
        byte[] a = engine.applyEdits(doc.filled(), removals, Map.of(), analysis.hasAcroForm());
        byte[] b = engine.applyEdits(doc.filled(), removals, Map.of(), analysis.hasAcroForm());
        assertThat(a).isEqualTo(b);
    }
}
