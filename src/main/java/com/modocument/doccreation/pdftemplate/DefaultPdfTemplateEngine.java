package com.modocument.doccreation.pdftemplate;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.infrastructure.file.OcrService;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The exact-PDF template engine (docs/doccreation/pdf-templates-contract.md §3). The template is the
 * original PDF; data is cut out of the content stream; values are drawn onto it. Code does all of it; the AI
 * (when the caller allows it) only improves labels and recall, and is validated against the real geometry.
 */
@Component
public class DefaultPdfTemplateEngine implements PdfTemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultPdfTemplateEngine.class);

    private final OcrService ocr;
    private final AiRegionAdvisor advisor;

    @Autowired
    public DefaultPdfTemplateEngine(OcrService ocr, AiFilesService ai, LlmRateLimiter llmQuota,
                                    CurrentUserProvider currentUser, PiiTokenizer tokenizer) {
        this(ocr, new AiRegionAdvisor(ai, llmQuota, currentUser, tokenizer));
    }

    DefaultPdfTemplateEngine(OcrService ocr, AiRegionAdvisor advisor) {
        this.ocr = ocr;
        this.advisor = advisor;
    }

    // ------------------------------------------------------------------ analyze

    @Override
    public AnalysisResult analyze(byte[] pdf, UploadFlow flow, AnalysisOptions options) {
        UploadFlow requested = flow == null ? UploadFlow.AUTO : flow;
        PDDocument doc = PdfDocuments.load(pdf);
        try {
            List<String> warnings = new ArrayList<>();
            List<PageModel> pages = new PdfAnalyzer(ocr).analyze(doc, warnings);
            List<PageFrame> frames = new ArrayList<>();
            for (PageModel p : pages) {
                frames.add(p.frame);
            }
            BlankRegionDetector blankDetector = new BlankRegionDetector();
            List<Candidate> blanks = blankDetector.detect(pages);
            boolean hasAcro = pages.stream().anyMatch(p -> !p.widgets.isEmpty());
            long acroValues = pages.stream().flatMap(p -> p.widgets.stream())
                    .filter(w -> w.value() != null && !w.value().isBlank()).count();
            SampleDataDetector dataDetector = new SampleDataDetector();
            List<Candidate> data = requested == UploadFlow.BLANK ? new ArrayList<>()
                    : new ArrayList<>(dataDetector.detect(pages, blanks));
            boolean looksFilled = looksFilled(acroValues, data, blanks);
            UploadFlow used = requested == UploadFlow.AUTO
                    ? (looksFilled ? UploadFlow.WITH_DATA : UploadFlow.BLANK) : requested;
            if (used == UploadFlow.BLANK) {
                data.clear();
            }
            boolean aiUsed = false;
            List<Candidate> merged;
            if (options != null && options.aiAllowed() && advisor != null && advisor.available()) {
                try {
                    if (used == UploadFlow.WITH_DATA) {
                        aiUsed = advisor.improveData(pages, data, blanks, dataDetector, warnings);
                    }
                    merged = RegionAssembler.merge(blanks, data);
                    aiUsed |= advisor.improveRegions(pages, merged, warnings);
                } catch (RuntimeException e) {
                    log.warn("AI assistance failed; using the code result: {}", e.getMessage());
                    merged = RegionAssembler.merge(blanks, data);
                    aiUsed = false;
                }
            } else {
                merged = RegionAssembler.merge(blanks, data);
            }
            RegionAssembler.finish(merged, pages);
            String seed = new String(PdfDocuments.sha256(pdf), StandardCharsets.ISO_8859_1);
            List<TemplateRegion> regions = new ArrayList<>();
            List<TemplateEdit> removals = new ArrayList<>();
            int low = 0;
            for (Candidate c : merged) {
                if (used == UploadFlow.BLANK) {
                    c.sample = null;
                }
                PageFrame frame = frames.get(c.page);
                regions.add(RegionAssembler.toRegion(c, frame));
                if (used == UploadFlow.WITH_DATA && c.removeBox != null) {
                    removals.add(RegionAssembler.toRemoval(c, frame, seed));
                    if (c.confidence < SampleDataDetector.REVIEW_THRESHOLD) {
                        low++;
                    }
                }
            }
            if (low > 0) {
                warnings.add(low + " possible sample value(s) have low confidence; please review them.");
            }
            if (used == UploadFlow.WITH_DATA && hasAcro && acroValues > 0) {
                warnings.add("Form field values will be cleared from the template.");
            }
            List<PageInfo> infos = new ArrayList<>();
            for (PageFrame f : frames) {
                infos.add(f.info());
            }
            return new AnalysisResult(pages.size(), infos, used, hasAcro, looksFilled, regions, removals,
                    List.copyOf(warnings), aiUsed ? "CODE+AI" : "CODE");
        } finally {
            PdfDocuments.closeQuietly(doc);
        }
    }

    /** Filled when form fields carry values, or at least two confident label/value pairs were found. */
    static boolean looksFilled(long acroValues, List<Candidate> data, List<Candidate> blanks) {
        if (acroValues > 0) {
            return true;
        }
        long strong = data.stream().filter(c -> c.confidence >= 0.75).count();
        long emptyLabelled = blanks.stream().filter(c -> c.origin != Candidate.Origin.ACRO && c.confidence >= 0.7)
                .filter(b -> data.stream().noneMatch(d -> d.mergedBlank == b)).count();
        return strong >= 2 && strong >= 0.4 * (strong + emptyLabelled);
    }

    // ------------------------------------------------------------------ edits

    @Override
    public byte[] applyEdits(byte[] originalPdf, List<TemplateEdit> edits, Map<String, byte[]> images,
                             boolean clearAcroForm) {
        List<TemplateEdit> list = edits == null ? List.of() : edits;
        if (list.isEmpty() && !clearAcroForm) {
            PdfDocuments.closeQuietly(PdfDocuments.load(originalPdf));
            return originalPdf;
        }
        Map<String, byte[]> imgs = images == null ? Map.of() : images;
        PDDocument doc = PdfDocuments.load(originalPdf);
        try {
            List<PageFrame> frames = PdfDocuments.frames(doc);
            FontResolver fonts = new FontResolver(doc);
            Map<Integer, List<TemplateEdit>> byPage = new TreeMap<>();
            for (TemplateEdit e : list) {
                if (e == null || e.box() == null || e.kind() == null || e.box().page() < 0
                        || e.box().page() >= frames.size()) {
                    continue;
                }
                byPage.computeIfAbsent(e.box().page(), k -> new ArrayList<>()).add(e);
            }
            for (Map.Entry<Integer, List<TemplateEdit>> entry : byPage.entrySet()) {
                int p = entry.getKey();
                PageFrame frame = frames.get(p);
                try (PageCanvas canvas = new PageCanvas(doc, doc.getPage(p), frame)) {
                    applyPage(doc, p, frame, entry.getValue(), imgs, fonts, canvas);
                }
            }
            if (clearAcroForm) {
                PdfContentEditor.clearAcroForm(doc);
            }
            return PdfDocuments.save(doc, editSeed(originalPdf, list, imgs, clearAcroForm));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not apply the template edits.", e);
        } finally {
            PdfDocuments.closeQuietly(doc);
        }
    }

    private static void applyPage(PDDocument doc, int p, PageFrame frame, List<TemplateEdit> edits,
                                  Map<String, byte[]> images, FontResolver fonts, PageCanvas canvas) throws IOException {
        List<Rect> boxes = new ArrayList<>();
        List<Boolean> paint = new ArrayList<>();
        List<TemplateEdit> replaces = new ArrayList<>();
        for (TemplateEdit e : edits) {
            if (e.kind() == EditKind.REMOVE || e.kind() == EditKind.REPLACE_TEXT) {
                boxes.add(Rect.ofBox(e.box()));
                paint.add(e.kind() == EditKind.REMOVE);
                if (e.kind() == EditKind.REPLACE_TEXT) {
                    replaces.add(e);
                }
            }
        }
        PdfContentEditor.PageRemoval removal = boxes.isEmpty() ? null
                : PdfContentEditor.removeInBoxes(doc, p, boxes, paint, canvas);
        for (TemplateEdit e : edits) {
            switch (e.kind()) {
                case REPLACE_TEXT -> replaceText(e, frame, removal, fonts, canvas);
                case ADD_TEXT -> addText(e, frame, fonts, canvas);
                case ADD_IMAGE -> {
                    byte[] bytes = e.imageKey() == null ? null : images.get(e.imageKey());
                    if (bytes != null && bytes.length > 0) {
                        Rect box = frame.toDisplay(Rect.ofBox(e.box()));
                        PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, e.imageKey());
                        canvas.beginClip(box);
                        canvas.image(img, box);
                        canvas.endClip();
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void replaceText(TemplateEdit e, PageFrame frame, PdfContentEditor.PageRemoval removal,
                                    FontResolver fonts, PageCanvas canvas) throws IOException {
        String text = e.text() == null ? "" : TextLayout.clean(e.text()).replace('\n', ' ');
        if (text.isBlank()) {
            return;
        }
        Rect box = Rect.ofBox(e.box());
        Glyph first = null;
        if (removal != null) {
            for (Glyph g : removal.removedGlyphs()) {
                float[] c = g.centre();
                if (box.contains(c[0], c[1]) && !g.isSpace() && (first == null || g.seq < first.seq)) {
                    first = g;
                }
            }
        }
        Rect display = frame.toDisplay(box);
        PDFont font;
        float size;
        int rgb;
        float x;
        float baseline;
        if (first != null) {
            String fontName = e.fontName() != null ? e.fontName() : first.fontName;
            font = e.fontName() == null && first.font != null && first.font.isEmbedded()
                    && FontResolver.covers(first.font, text) ? first.font : fonts.choose(fontName, text);
            size = e.fontSize() != null && e.fontSize() > 0 ? e.fontSize() : first.size;
            rgb = e.color() != null ? RegionAssembler.parseHex(e.color(), first.rgb) : first.rgb;
            float[] o = frame.toDisplay(first.originX, first.originY);
            x = o[0];
            baseline = o[1];
        } else {
            font = fonts.choose(e.fontName() == null ? "LiberationSans" : e.fontName(), text);
            size = e.fontSize() != null && e.fontSize() > 0 ? e.fontSize() : Math.max(6f, display.h() * 0.7f);
            rgb = RegionAssembler.parseHex(e.color(), 0);
            x = display.x0();
            float[] m = FontResolver.metrics(font);
            baseline = display.y0() - m[1] * size;
        }
        canvas.text(font, size, rgb, x, baseline, text);
    }

    private static void addText(TemplateEdit e, PageFrame frame, FontResolver fonts, PageCanvas canvas)
            throws IOException {
        String text = e.text() == null ? "" : e.text();
        if (text.isBlank()) {
            return;
        }
        Rect box = frame.toDisplay(Rect.ofBox(e.box()));
        PDFont font = fonts.choose(e.fontName() == null ? "LiberationSans" : e.fontName(), text);
        float start = e.fontSize() != null && e.fontSize() > 0 ? e.fontSize() : 0;
        boolean multi = text.contains("\n") || box.h() > 2.5f * (start > 0 ? start : 12f);
        TextLayout.Fit fit = TextLayout.fit(font, text, box.w(), box.h(), start, 4f, 0, multi);
        float[] m = FontResolver.metrics(font);
        float baseline = box.y1() - m[0] * fit.size();
        int rgb = RegionAssembler.parseHex(e.color(), 0);
        canvas.beginClip(box);
        for (String line : fit.lines()) {
            canvas.text(font, fit.size(), rgb, box.x0() + 1, baseline, line);
            baseline -= fit.size() * TextLayout.LINE_GAP;
        }
        canvas.endClip();
    }

    private static byte[] editSeed(byte[] original, List<TemplateEdit> edits, Map<String, byte[]> images,
                                   boolean clear) {
        StringBuilder sb = new StringBuilder();
        List<TemplateEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparing(e -> String.valueOf(e.id())));
        for (TemplateEdit e : sorted) {
            sb.append(e.kind()).append(e.box()).append(e.text()).append(e.fontName()).append(e.fontSize())
                    .append(e.color()).append(e.imageKey()).append('\u0000');
        }
        sb.append(clear);
        List<byte[]> parts = new ArrayList<>();
        parts.add(original);
        parts.add(sb.toString().getBytes(StandardCharsets.UTF_8));
        new TreeMap<>(images).forEach((k, v) -> parts.add(PdfDocuments.sha256(v)));
        return PdfDocuments.sha256(parts.toArray(new byte[0][]));
    }

    // ------------------------------------------------------------------ fill, pages, render, verify

    @Override
    public FillResult fill(byte[] templatePdf, List<TemplateRegion> regions, Map<String, String> values,
                           Map<String, byte[]> images, FillOptions options) {
        return new PdfTemplateFiller().fill(templatePdf, regions, values, images, options);
    }

    @Override
    public List<PageInfo> pages(byte[] pdf) {
        PDDocument doc = PdfDocuments.load(pdf);
        try {
            List<PageInfo> out = new ArrayList<>();
            for (PageFrame f : PdfDocuments.frames(doc)) {
                out.add(f.info());
            }
            return out;
        } finally {
            PdfDocuments.closeQuietly(doc);
        }
    }

    @Override
    public byte[] renderPage(byte[] pdf, int page, int dpi) {
        return PdfRendering.renderPng(pdf, page, dpi);
    }

    @Override
    public ExactnessReport verify(byte[] base, byte[] candidate, List<PdfBox> allowedAreas, int dpi) {
        return PdfRendering.verify(base, candidate, allowedAreas, dpi, -1);
    }
}
