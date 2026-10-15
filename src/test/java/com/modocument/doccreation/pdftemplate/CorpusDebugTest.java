package com.modocument.doccreation.pdftemplate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Prints what the engine finds on each corpus document (run with -Dpdftemplate.debug=true). */
@EnabledIfSystemProperty(named = "pdftemplate.debug", matches = "true")
class CorpusDebugTest {

    @Test
    void largeDocumentTiming() throws Exception {
        org.apache.pdfbox.multipdf.PDFMergerUtility merger = new org.apache.pdfbox.multipdf.PDFMergerUtility();
        try (org.apache.pdfbox.pdmodel.PDDocument out = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < 100; i++) {
                for (PdfCorpus.Doc d : java.util.List.of(PdfCorpus.tableForm(), PdfCorpus.flatForm(),
                        PdfCorpus.letterhead())) {
                    try (var src = org.apache.pdfbox.Loader.loadPDF(d.filled())) {
                        merger.appendDocument(out, src);
                    }
                }
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            out.save(bytes);
            DefaultPdfTemplateEngine engine = CorpusSupport.engine();
            long t0 = System.nanoTime();
            AnalysisResult r = engine.analyze(bytes.toByteArray(), UploadFlow.WITH_DATA, CorpusSupport.codeOnly());
            long t1 = System.nanoTime();
            byte[] tpl = engine.applyEdits(bytes.toByteArray(), r.removals(), java.util.Map.of(), false);
            long t2 = System.nanoTime();
            System.out.println("TIMING pages=" + r.pageCount() + " regions=" + r.regions().size() + " removals="
                    + r.removals().size() + " analyze=" + (t1 - t0) / 1_000_000 + "ms applyEdits="
                    + (t2 - t1) / 1_000_000 + "ms size=" + bytes.size() + "->" + tpl.length);
        }
    }

    @Test
    void dump() {
        DefaultPdfTemplateEngine engine = CorpusSupport.engine();
        for (PdfCorpus.Doc doc : PdfCorpus.all()) {
            System.out.println("==================== " + doc.name());
            AnalysisResult blank = engine.analyze(doc.blank(), UploadFlow.BLANK, CorpusSupport.codeOnly());
            System.out.println("-- BLANK regions");
            for (TemplateRegion r : blank.regions()) {
                System.out.println("  " + r.key() + " | " + r.label() + " | " + r.group() + " | " + r.kind() + " | "
                        + r.box() + " | " + r.source() + " " + r.confidence());
            }
            System.out.println("  truth: " + doc.regions());
            AnalysisResult filled = engine.analyze(doc.filled(), UploadFlow.AUTO, CorpusSupport.codeOnly());
            System.out.println("-- FILLED flow=" + filled.flowUsed() + " looksFilled=" + filled.looksFilled()
                    + " warnings=" + filled.warnings());
            for (TemplateRegion r : filled.regions()) {
                System.out.println("  " + r.key() + " | " + r.label() + " | " + r.group() + " | " + r.kind() + " | "
                        + r.box() + " | " + r.source() + " " + r.confidence() + " | " + r.sampleValue() + " | "
                        + r.fontName() + " " + r.fontSize() + " " + r.color() + " " + r.align());
            }
            for (TemplateEdit e : filled.removals()) {
                System.out.println("  REMOVE " + e.box() + " " + e.confidence() + " '" + e.removedText() + "'");
            }
            CorpusSupport.Metrics m = CorpusSupport.dataMetrics(doc, filled);
            System.out.println("  precision=" + m.precision() + " recall=" + m.recall() + " fp=" + m.falsePositives()
                    + " missed=" + m.missed());
        }
    }
}
