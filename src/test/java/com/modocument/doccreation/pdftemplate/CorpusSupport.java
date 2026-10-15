package com.modocument.doccreation.pdftemplate;

import com.modocument.infrastructure.file.OcrService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared engine wiring and metrics for the corpus tests. */
final class CorpusSupport {

    static final String TESSDATA = "/usr/share/tesseract-ocr/5/tessdata";
    static final double APPLY_THRESHOLD = SampleDataDetector.REVIEW_THRESHOLD;

    private static OcrService ocr;

    private CorpusSupport() {
    }

    static synchronized OcrService ocr() {
        if (ocr == null) {
            String dir = Files.isDirectory(Path.of(TESSDATA)) ? TESSDATA : "tessdata";
            ocr = new OcrService(dir);
        }
        return ocr;
    }

    static boolean ocrAvailable() {
        return ocr().isAvailable();
    }

    static DefaultPdfTemplateEngine engine() {
        return new DefaultPdfTemplateEngine(ocr(), null);
    }

    static AnalysisOptions codeOnly() {
        return new AnalysisOptions(false, "en");
    }

    /** Removals the human would accept by default (confidence at or above the review line). */
    static List<TemplateEdit> applied(AnalysisResult r) {
        List<TemplateEdit> out = new ArrayList<>();
        for (TemplateEdit e : r.removals()) {
            if (e.confidence() >= APPLY_THRESHOLD) {
                out.add(e);
            }
        }
        return out;
    }

    static boolean contains(PdfBox outer, PdfBox inner) {
        if (outer.page() != inner.page()) {
            return false;
        }
        float cx = inner.x() + inner.w() / 2;
        float cy = inner.y() + inner.h() / 2;
        return cx >= outer.x() && cx <= outer.x() + outer.w() && cy >= outer.y() && cy <= outer.y() + outer.h();
    }

    static boolean overlaps(PdfBox a, PdfBox b) {
        return a.page() == b.page() && (contains(a, b) || contains(b, a));
    }

    /** Detection metrics of one document's data (removals + AcroForm sample values). */
    record Metrics(int truePositives, int proposed, int truth, List<String> falsePositives, List<String> missed) {
        double precision() {
            return proposed == 0 ? 1 : truePositives / (double) proposed;
        }

        double recall() {
            return truth == 0 ? 1 : (truth - missed.size()) / (double) truth;
        }
    }

    static Metrics dataMetrics(PdfCorpus.Doc doc, AnalysisResult r) {
        List<PdfBox> found = new ArrayList<>();
        List<String> foundText = new ArrayList<>();
        for (TemplateEdit e : applied(r)) {
            found.add(e.box());
            foundText.add(e.removedText());
        }
        for (TemplateRegion reg : r.regions()) {
            if (reg.acroFieldName() != null && reg.sampleValue() != null) {
                found.add(reg.box());
                foundText.add(reg.sampleValue());
            }
        }
        int tp = 0;
        List<String> fps = new ArrayList<>();
        boolean[] hit = new boolean[doc.values().size()];
        for (int i = 0; i < found.size(); i++) {
            boolean matched = false;
            for (int j = 0; j < doc.values().size(); j++) {
                if (overlaps(found.get(i), doc.values().get(j).box())) {
                    hit[j] = true;
                    matched = true;
                }
            }
            if (matched) {
                tp++;
            } else {
                fps.add(foundText.get(i) + " @" + found.get(i));
            }
        }
        List<String> missed = new ArrayList<>();
        for (int j = 0; j < hit.length; j++) {
            if (!hit[j]) {
                missed.add(doc.values().get(j).text());
            }
        }
        return new Metrics(tp, found.size(), doc.values().size(), fps, missed);
    }

    static String text(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            return s.getText(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Text in content order (values drawn on leaders are not interleaved with the underscores). */
    static String contentOrderText(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Regions a reviewer would keep by default: everything except data regions below the review line. */
    static List<TemplateRegion> accepted(AnalysisResult r) {
        List<TemplateRegion> out = new ArrayList<>();
        for (TemplateRegion reg : r.regions()) {
            if (reg.source() == RegionSource.DATA_REMOVED && reg.confidence() < APPLY_THRESHOLD) {
                continue;
            }
            out.add(reg);
        }
        return out;
    }

    /** Word multiset of the extracted text. */
    static Map<String, Integer> words(byte[] pdf) {
        Map<String, Integer> out = new HashMap<>();
        for (String w : text(pdf).split("\\s+")) {
            if (!w.isBlank()) {
                out.merge(w, 1, Integer::sum);
            }
        }
        return out;
    }

    static List<PdfBox> boxes(List<TemplateEdit> edits) {
        List<PdfBox> out = new ArrayList<>();
        for (TemplateEdit e : edits) {
            out.add(e.box());
        }
        return out;
    }
}
