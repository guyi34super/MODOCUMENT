package com.modocument.doccreation.pdftemplate;

import com.modocument.infrastructure.file.OcrService;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDPushButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds the {@link PageModel}s of a document: text runs, rulings, cells, images, widgets, OCR words. */
final class PdfAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PdfAnalyzer.class);
    static final int OCR_DPI = 300;
    /** OCR is slow (seconds per page); beyond this many pages, scanned pages are left for manual regions. */
    static final int MAX_OCR_PAGES = 40;
    private int ocrPages;

    private final OcrService ocr;
    private int runCounter;

    PdfAnalyzer(OcrService ocr) {
        this.ocr = ocr;
    }

    List<PageModel> analyze(PDDocument doc, List<String> warnings) {
        List<PageModel> pages = new ArrayList<>();
        Map<COSDictionary, Integer> annotPages = new HashMap<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                for (PDAnnotation a : doc.getPage(i).getAnnotations()) {
                    annotPages.put(a.getCOSObject(), i);
                }
            } catch (IOException | RuntimeException ignored) {
                // no annotations readable on that page
            }
        }
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            pages.add(analyzePage(doc, i, warnings));
        }
        collectWidgets(doc, pages, annotPages);
        return pages;
    }

    private PageModel analyzePage(PDDocument doc, int index, List<String> warnings) {
        PDPage page = doc.getPage(index);
        PageFrame frame = PageFrame.of(index, page);
        PageModel model = new PageModel(frame);
        ContentWalker w = new ContentWalker(page, index);
        try {
            w.walk();
        } catch (IOException | RuntimeException e) {
            warnings.add("Page " + (index + 1) + ": part of the content could not be read.");
            log.warn("Page {} walk failed: {}", index + 1, e.getMessage());
        }
        for (Glyph g : w.glyphs) {
            toDisplay(g, frame);
            model.glyphs.add(g);
        }
        for (Shapes.Segment s : w.segments) {
            Rect d = frame.toDisplay(s.rect());
            if (d.h() < 0.01f) {
                model.segments.add(new Shapes.Segment(true, d.y0(), d.x0(), d.x1()));
            } else {
                model.segments.add(new Shapes.Segment(false, d.x0(), d.y0(), d.y1()));
            }
        }
        for (Shapes.FilledRect f : w.fills) {
            model.fills.add(new Shapes.FilledRect(frame.toDisplay(f.rect()), f.rgb()));
        }
        for (Shapes.ImagePlacement img : w.images) {
            model.images.add(new Shapes.ImagePlacement(frame.toDisplay(img.rect()), img.loc()));
        }
        // scanned / outlined / garbled pages
        long real = model.glyphs.stream().filter(g -> !g.isSpace() && !garbled(g.text)).count();
        long bad = model.glyphs.stream().filter(g -> !g.isSpace() && garbled(g.text)).count();
        float pageArea = frame.width * frame.height;
        double imageCover = 0;
        for (Shapes.ImagePlacement img : model.images) {
            Rect r = img.rect().intersect(new Rect(0, 0, frame.displayWidth(), frame.displayHeight()));
            imageCover += r == null ? 0 : r.area();
        }
        boolean scanned = real < 3 && (imageCover > 0.3 * pageArea || w.curveFills > 40);
        boolean garbledPage = bad >= 20 && bad > real;
        model.needsOcr = scanned || garbledPage;
        List<Glyph> runGlyphs = new ArrayList<>();
        for (Glyph g : model.glyphs) {
            if (!garbled(g.text) || g.isSpace()) {
                runGlyphs.add(g);
            }
        }
        if (model.needsOcr) {
            List<Glyph> words = ocrWords(doc, frame, warnings);
            if (!words.isEmpty()) {
                model.ocrUsed = true;
                model.glyphs.addAll(words);
                runGlyphs = garbledPage ? words : merge(runGlyphs, words);
            }
        }
        buildRuns(runGlyphs, model);
        model.buildLines();
        model.bodySize = bodySize(model.runs);
        CellDetector.Result cells = CellDetector.detect(model.segments, model.fills, frame.displayWidth(),
                frame.displayHeight());
        model.cells.addAll(cells.cells());
        model.freeLines.addAll(cells.freeLines());
        return model;
    }

    private static List<Glyph> merge(List<Glyph> a, List<Glyph> b) {
        List<Glyph> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    static boolean garbled(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '�' || c >= '' && c <= '' || Character.isISOControl(c) && c != '\t') {
                return true;
            }
        }
        return false;
    }

    static void toDisplay(Glyph g, PageFrame frame) {
        g.dbox = frame.toDisplay(g.box);
        g.dink = g.ink == null ? null : frame.toDisplay(g.ink);
        float[] o = frame.toDisplay(g.originX, g.originY);
        g.dOriginX = o[0];
        g.dOriginY = o[1];
        float dx;
        float dy;
        switch (frame.rotation) {
            case 90 -> {
                dx = g.dirY;
                dy = -g.dirX;
            }
            case 180 -> {
                dx = -g.dirX;
                dy = -g.dirY;
            }
            case 270 -> {
                dx = -g.dirY;
                dy = g.dirX;
            }
            default -> {
                dx = g.dirX;
                dy = g.dirY;
            }
        }
        g.horizontal = dx > 0.98f && Math.abs(dy) < 0.2f;
    }

    private List<Glyph> ocrWords(PDDocument doc, PageFrame frame, List<String> warnings) {
        if (ocr == null) {
            warnings.add("Page " + (frame.index + 1) + " has no readable text (scan or outlines) and OCR is off.");
            return List.of();
        }
        if (++ocrPages > MAX_OCR_PAGES) {
            if (ocrPages == MAX_OCR_PAGES + 1) {
                warnings.add("Only the first " + MAX_OCR_PAGES + " scanned pages were read with OCR.");
            }
            return List.of();
        }
        try {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(frame.index, OCR_DPI, ImageType.RGB);
            List<OcrService.OcrWord> words = ocr.words(img);
            if (words.isEmpty()) {
                warnings.add("Page " + (frame.index + 1) + " has no readable text and OCR found none.");
                return List.of();
            }
            float s = OCR_DPI / 72f;
            float dh = frame.displayHeight();
            List<Glyph> out = new ArrayList<>();
            int seq = 1_000_000;
            for (OcrService.OcrWord word : words) {
                if (word.confidence() < 30) {
                    continue;
                }
                java.awt.Rectangle b = word.box();
                Rect d = new Rect(b.x / s, dh - (b.y + b.height) / s, (b.x + b.width) / s, dh - b.y / s);
                float size = Math.max(4f, Math.min(48f, d.h() / 0.78f));
                Rect bs = frame.toBoxSpace(d);
                Glyph g = new Glyph(frame.index, seq++, word.text(), null, "OCR", size, 0, false, false, bs, bs,
                        bs.x0(), bs.y0(), 1, 0, -1, null, 0, true);
                g.dbox = d;
                g.dink = d;
                g.dOriginX = d.x0();
                g.dOriginY = d.y0() + 0.18f * d.h();
                g.horizontal = true;
                out.add(g);
            }
            return out;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            warnings.add("Page " + (frame.index + 1) + ": OCR failed.");
            log.warn("OCR of page {} failed: {}", frame.index + 1, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ runs

    private void buildRuns(List<Glyph> glyphs, PageModel model) {
        List<Glyph> current = new ArrayList<>();
        Glyph prev = null;
        for (Glyph g : glyphs) {
            if (!g.horizontal) {
                continue;
            }
            if (prev != null && breaks(prev, g)) {
                flush(current, model);
            }
            current.add(g);
            prev = g;
        }
        flush(current, model);
    }

    private static boolean breaks(Glyph p, Glyph g) {
        float size = Math.max(p.size, g.size);
        if (Math.abs(g.dOriginY - p.dOriginY) > 0.3f * size) {
            return true;
        }
        if (Math.abs(g.size - p.size) > (g.ocr ? 0.5f : 0.2f) * size) {
            return true;
        }
        float gap = g.dbox.x0() - p.dbox.x1();
        if (gap < -0.5f * size || gap > (g.ocr ? 1.2f : 0.9f) * size) {
            return true;
        }
        if (g.isSpace() || p.isSpace()) {
            return false;
        }
        return g.rgb != p.rgb || g.bold != p.bold || !g.fontName.equals(p.fontName);
    }

    private void flush(List<Glyph> current, PageModel model) {
        while (!current.isEmpty() && current.get(0).isSpace()) {
            current.remove(0);
        }
        while (!current.isEmpty() && current.get(current.size() - 1).isSpace()) {
            current.remove(current.size() - 1);
        }
        if (!current.isEmpty()) {
            model.runs.add(new TextRun("r" + (runCounter++), current));
        }
        current.clear();
    }

    private static float bodySize(List<TextRun> runs) {
        List<Float> sizes = new ArrayList<>();
        for (TextRun r : runs) {
            for (int i = 0; i < Math.min(r.text.length(), 200); i++) {
                sizes.add(r.size);
            }
        }
        if (sizes.isEmpty()) {
            return 10f;
        }
        Float[] a = sizes.toArray(new Float[0]);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    // ------------------------------------------------------------------ AcroForm

    private static void collectWidgets(PDDocument doc, List<PageModel> pages, Map<COSDictionary, Integer> annotPages) {
        PDAcroForm form;
        try {
            form = doc.getDocumentCatalog().getAcroForm();
        } catch (RuntimeException e) {
            return;
        }
        if (form == null) {
            return;
        }
        for (PDField field : form.getFieldTree()) {
            if (!(field instanceof PDTerminalField terminal) || field instanceof PDPushButton) {
                continue;
            }
            String type = typeOf(field);
            float fontSize = 0;
            String fontName = "Helvetica";
            int rgb = 0;
            Align align = Align.LEFT;
            boolean multiline = false;
            if (field instanceof PDVariableText vt) {
                String[] da = parseDa(vt.getDefaultAppearance());
                fontSize = parseFloat(da[1], 0);
                rgb = parseColour(da[2]);
                fontName = resolveDaFont(form, vt, da[0]);
                align = switch (vt.getQ()) {
                    case 1 -> Align.CENTER;
                    case 2 -> Align.RIGHT;
                    default -> Align.LEFT;
                };
            }
            if (field instanceof PDTextField tf) {
                multiline = tf.isMultiline();
            }
            List<PDAnnotationWidget> widgets = terminal.getWidgets();
            for (int wi = 0; wi < widgets.size(); wi++) {
                PDAnnotationWidget widget = widgets.get(wi);
                Integer pageIndex = annotPages.get(widget.getCOSObject());
                if (pageIndex == null || widget.getRectangle() == null) {
                    continue;
                }
                PageModel model = pages.get(pageIndex);
                PageFrame frame = model.frame;
                var r = widget.getRectangle();
                Rect boxSpace = new Rect(r.getLowerLeftX() - frame.llx, r.getLowerLeftY() - frame.lly,
                        r.getUpperRightX() - frame.llx, r.getUpperRightY() - frame.lly);
                String value = valueOf(field, widget);
                String name = field.getFullyQualifiedName();
                if (widgets.size() > 1 && field instanceof PDButton) {
                    name = name + "#" + wi;
                }
                model.widgets.add(new PageModel.Widget(pageIndex, frame.toDisplay(boxSpace), name,
                        field.getPartialName(), field.getAlternateFieldName(), type, value, fontSize, fontName, rgb,
                        field.isRequired(), align, multiline));
            }
        }
    }

    private static String typeOf(PDField field) {
        if (field instanceof PDTextField tf) {
            return tf.isMultiline() ? "MULTILINE" : "TEXT";
        }
        if (field instanceof PDCheckBox) {
            return "CHECKBOX";
        }
        if (field instanceof PDRadioButton) {
            return "RADIO";
        }
        if (field instanceof PDChoice) {
            return "CHOICE";
        }
        if (field instanceof PDSignatureField) {
            return "SIGNATURE";
        }
        return "TEXT";
    }

    private static String valueOf(PDField field, PDAnnotationWidget widget) {
        try {
            if (field instanceof PDCheckBox cb) {
                return cb.isChecked() ? "true" : "";
            }
            if (field instanceof PDRadioButton) {
                COSName as = widget.getAppearanceState();
                return as != null && !COSName.Off.equals(as) ? "true" : "";
            }
            if (field instanceof PDSignatureField sig) {
                return sig.getValue() != null ? "[signature]" : "";
            }
            String v = field.getValueAsString();
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** [fontResourceName, size, colour operands+op] from a DA string like "/Helv 10 Tf 0 0 1 rg". */
    static String[] parseDa(String da) {
        String[] out = {"", "0", ""};
        if (da == null) {
            return out;
        }
        String[] t = da.trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            if ("Tf".equals(t[i]) && i >= 2) {
                out[0] = t[i - 2].startsWith("/") ? t[i - 2].substring(1) : t[i - 2];
                out[1] = t[i - 1];
            } else if ("g".equals(t[i]) && i >= 1) {
                out[2] = t[i - 1] + " g";
            } else if ("rg".equals(t[i]) && i >= 3) {
                out[2] = t[i - 3] + " " + t[i - 2] + " " + t[i - 1] + " rg";
            }
        }
        return out;
    }

    private static int parseColour(String c) {
        if (c == null || c.isBlank()) {
            return 0;
        }
        String[] t = c.split(" ");
        try {
            if (t.length == 2) {
                int v = Math.round(Float.parseFloat(t[0]) * 255);
                return (v << 16) | (v << 8) | v;
            }
            if (t.length == 4) {
                int r = Math.round(Float.parseFloat(t[0]) * 255);
                int g = Math.round(Float.parseFloat(t[1]) * 255);
                int b = Math.round(Float.parseFloat(t[2]) * 255);
                return (r << 16) | (g << 8) | b;
            }
        } catch (NumberFormatException ignored) {
            // default black
        }
        return 0;
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s);
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String resolveDaFont(PDAcroForm form, PDVariableText vt, String resourceName) {
        try {
            PDResources dr = form.getDefaultResources();
            if (dr != null && !resourceName.isEmpty()) {
                PDFont f = dr.getFont(COSName.getPDFName(resourceName));
                if (f != null && f.getName() != null) {
                    return f.getName();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fall back
        }
        return "Helv".equals(resourceName) || resourceName.isEmpty() ? "Helvetica" : resourceName;
    }
}
