package com.modocument.doccreation.pdftemplate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Code-only detection on the corpus: regions of the blank twins, data of the filled twins. */
class PdfTemplateDetectionTest {

    private final DefaultPdfTemplateEngine engine = CorpusSupport.engine();

    static Stream<PdfCorpus.Doc> docs() {
        return PdfCorpus.all().stream();
    }

    private List<PdfCorpus.Doc> usable() {
        List<PdfCorpus.Doc> out = new ArrayList<>();
        for (PdfCorpus.Doc d : PdfCorpus.all()) {
            if (!d.needsOcr() || CorpusSupport.ocrAvailable()) {
                out.add(d);
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void everyFormRegionOfTheBlankTwinIsFound(PdfCorpus.Doc doc) {
        AnalysisResult r = engine.analyze(doc.blank(), UploadFlow.BLANK, CorpusSupport.codeOnly());
        assertThat(r.flowUsed()).isEqualTo(UploadFlow.BLANK);
        assertThat(r.removals()).isEmpty();
        assertThat(r.detectedBy()).isEqualTo("CODE");
        for (PdfCorpus.Region truth : doc.regions()) {
            TemplateRegion hit = r.regions().stream().filter(x -> CorpusSupport.overlaps(x.box(), truth.box()))
                    .findFirst().orElse(null);
            assertThat(hit).as("%s: %s region '%s' at %s", doc.name(), truth.type(), truth.label(), truth.box())
                    .isNotNull();
            assertThat(hit.kind()).as("%s: kind of '%s'", doc.name(), truth.label()).isEqualTo(truth.kind());
            assertThat(hit.label().toLowerCase(Locale.ROOT))
                    .as("%s: label of '%s'", doc.name(), truth.label())
                    .contains(truth.label().toLowerCase(Locale.ROOT).split(" ")[0]);
            assertThat(hit.sampleValue()).isNull();
        }
        Set<String> keys = new HashSet<>();
        for (TemplateRegion x : r.regions()) {
            assertThat(x.key()).matches("[a-z0-9_]{1,64}");
            assertThat(keys.add(x.key())).as("unique key %s", x.key()).isTrue();
            assertThat(x.label().length()).isLessThanOrEqualTo(200);
            assertThat(x.color()).matches("#[0-9A-F]{6}");
        }
    }

    @Test
    void dataDetectionPrecisionAndRecall() {
        int tp = 0;
        int proposed = 0;
        int truth = 0;
        int missed = 0;
        StringBuilder report = new StringBuilder("Data detection (code only, confidence >= 0.6):\n");
        for (PdfCorpus.Doc doc : usable()) {
            AnalysisResult r = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
            CorpusSupport.Metrics m = CorpusSupport.dataMetrics(doc, r);
            report.append(String.format(Locale.ROOT, "  %-13s precision %.3f (%d/%d)  recall %.3f (%d/%d)  fp=%s missed=%s%n",
                    doc.name(), m.precision(), m.truePositives(), m.proposed(), m.recall(),
                    m.truth() - m.missed().size(), m.truth(), m.falsePositives(), m.missed()));
            tp += m.truePositives();
            proposed += m.proposed();
            truth += m.truth();
            missed += m.missed().size();
        }
        double precision = proposed == 0 ? 1 : tp / (double) proposed;
        double recall = truth == 0 ? 1 : (truth - missed) / (double) truth;
        report.append(String.format(Locale.ROOT, "  TOTAL         precision %.3f  recall %.3f%n", precision, recall));
        System.out.println(report);
        assertThat(precision).as(report.toString()).isGreaterThanOrEqualTo(0.95);
        assertThat(recall).as(report.toString()).isGreaterThanOrEqualTo(0.9);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void autoFlowTellsBlankFromFilled(PdfCorpus.Doc doc) {
        if (doc.needsOcr() && !CorpusSupport.ocrAvailable()) {
            return;
        }
        assertThat(engine.analyze(doc.blank(), UploadFlow.AUTO, CorpusSupport.codeOnly()).flowUsed())
                .as(doc.name()).isEqualTo(UploadFlow.BLANK);
        AnalysisResult filled = engine.analyze(doc.filled(), UploadFlow.AUTO, CorpusSupport.codeOnly());
        assertThat(filled.flowUsed()).as(doc.name()).isEqualTo(UploadFlow.WITH_DATA);
        assertThat(filled.looksFilled()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("docs")
    void headingsAndLabelsAreNeverProposedAsData(PdfCorpus.Doc doc) {
        if (doc.needsOcr() && !CorpusSupport.ocrAvailable()) {
            return;
        }
        AnalysisResult r = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
        String blankText = CorpusSupport.text(doc.blank());
        for (TemplateEdit e : CorpusSupport.applied(r)) {
            String removed = e.removedText();
            if (removed == null || removed.startsWith("[") || "true".equals(removed)) {
                continue;
            }
            for (String line : removed.split("\n")) {
                assertThat(blankText).as("%s removes template text '%s'", doc.name(), line).doesNotContain(line.strip());
            }
        }
    }

    @Test
    void kindsGroupsAndFontsComeFromTheDocument() {
        AnalysisResult blank = engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, CorpusSupport.codeOnly());
        TemplateRegion name = find(blank, "full_name");
        assertThat(name.group()).isEqualTo("Personal information");
        assertThat(find(blank, "date_of_birth").kind()).isEqualTo(RegionKind.DATE);
        assertThat(find(blank, "signature").kind()).isEqualTo(RegionKind.SIGNATURE);
        assertThat(find(blank, "photo").kind()).isEqualTo(RegionKind.IMAGE);
        assertThat(find(blank, "stamp").kind()).isEqualTo(RegionKind.IMAGE);
        assertThat(find(blank, "signature").group()).isEqualTo("Declaration");
        // reading order: top to bottom
        assertThat(find(blank, "full_name").sortOrder()).isLessThan(find(blank, "signature").sortOrder());

        AnalysisResult letter = engine.analyze(PdfCorpus.letterhead().filled(), UploadFlow.WITH_DATA,
                CorpusSupport.codeOnly());
        TemplateRegion ref = find(letter, "ref");
        assertThat(ref.source()).isEqualTo(RegionSource.DATA_REMOVED);
        assertThat(ref.sampleValue()).isEqualTo("ACM-2024-0117");
        assertThat(ref.fontName()).endsWith("+LiberationSerif");
        assertThat(ref.fontSize()).isEqualTo(11f);
        assertThat(ref.color()).isEqualTo("#000000");
        // the letterhead contact line is offered for review only
        assertThat(letter.removals()).anyMatch(e -> e.removedText().contains("+44") && e.confidence() < 0.6);

        AnalysisResult table = engine.analyze(PdfCorpus.tableForm().filled(), UploadFlow.WITH_DATA,
                CorpusSupport.codeOnly());
        assertThat(find(table, "amount_1").align()).isEqualTo(Align.RIGHT);
        assertThat(find(table, "amount_1").kind()).isEqualTo(RegionKind.NUMBER);
        assertThat(find(table, "description_1").align()).isEqualTo(Align.LEFT);

        AnalysisResult greek = engine.analyze(PdfCorpus.cidGreek().filled(), UploadFlow.WITH_DATA,
                CorpusSupport.codeOnly());
        assertThat(greek.regions()).extracting(TemplateRegion::key).contains("onomateponymo", "poli", "imerominia");
        assertThat(find(greek, "onomateponymo").sampleValue()).isEqualTo("Γιώργος Παπαδόπουλος");
    }

    @Test
    void pageGeometryIsReported() {
        PdfCorpus.Doc doc = PdfCorpus.mixedPages();
        List<PageInfo> pages = engine.pages(doc.blank());
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0).width()).isEqualTo(842f);
        assertThat(pages.get(1).width()).isEqualTo(576f);
        assertThat(pages.get(1).height()).isEqualTo(756f);
        assertThat(pages.get(2).rotation()).isEqualTo(90);
        AnalysisResult r = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
        assertThat(r.pages()).isEqualTo(pages);
        // on the rotated page the field runs along the unrotated y axis
        TemplateRegion approved = find(r, "approved_by");
        assertThat(approved.box().page()).isEqualTo(2);
        assertThat(approved.box().h()).isGreaterThan(approved.box().w());
    }

    @Test
    void analysisIsDeterministic() {
        PdfCorpus.Doc doc = PdfCorpus.formXObject();
        AnalysisResult a = engine.analyze(doc.filled(), UploadFlow.AUTO, CorpusSupport.codeOnly());
        AnalysisResult b = engine.analyze(doc.filled(), UploadFlow.AUTO, CorpusSupport.codeOnly());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void contentsLinesAndLeadInColonsAreNotConfidentFields() throws java.io.IOException {
        byte[] pdf;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(
                    org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                PdfCorpus.text(cs, PdfCorpus.helvBold(), 14, 72, 780, "Contents");
                PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 750, "Introduction ........................ 3");
                PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 735, "Scope ............................... 7");
                PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 690, "The following conditions apply:");
                PdfCorpus.text(cs, PdfCorpus.helv(), 10, 72, 676, "all work is carried out by qualified staff only.");
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        AnalysisResult r = engine.analyze(pdf, UploadFlow.BLANK, CorpusSupport.codeOnly());
        assertThat(r.regions()).noneMatch(x -> x.box().y() > 730);
        assertThat(r.regions()).allMatch(x -> x.confidence() < 0.6);
    }

    private static TemplateRegion find(AnalysisResult r, String key) {
        return r.regions().stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + key + " in " + r.regions()));
    }
}
