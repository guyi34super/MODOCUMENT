package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes values into a template: text auto-fitted in the region's font (or a bundled Liberation font),
 * continuation pages for text that does not fit at the minimum size, check marks, images fitted in their
 * boxes, and AcroForm fields filled then flattened. Everything is drawn clipped to its region, in an
 * appended content stream, so the rest of the page renders exactly as the template.
 */
final class PdfTemplateFiller {

    private static final Logger log = LoggerFactory.getLogger(PdfTemplateFiller.class);
    static final float CONT_MARGIN = 50f;
    static final float HEADER_BAND = 0.15f;

    private record Overflow(TemplateRegion region, String text, PDFont font, float size) {
    }

    private record ContLine(int page, float x, float baseline, String text, PDFont font, float size, int rgb) {
    }

    FillResult fill(byte[] templatePdf, List<TemplateRegion> regions, Map<String, String> values,
                    Map<String, byte[]> images, FillOptions options) {
        Map<String, String> vals = values == null ? Map.of() : values;
        Map<String, byte[]> imgs = images == null ? Map.of() : images;
        float minSize = options == null || options.minFontSize() <= 0 ? 6f : options.minFontSize();
        boolean continuation = options == null || options.continuationPages();
        PDDocument doc = PdfDocuments.load(templatePdf);
        PDDocument headerSource = null;
        try {
            int originalPages = doc.getNumberOfPages();
            List<PageFrame> frames = PdfDocuments.frames(doc);
            FontResolver fonts = new FontResolver(doc);
            List<TemplateRegion> sorted = new ArrayList<>(regions == null ? List.of() : regions);
            sorted.sort(Comparator.comparingInt(TemplateRegion::sortOrder));

            List<TemplateRegion> drawn = fillAcroForm(doc, sorted, vals);

            // pass 1: fit everything, collect overflow
            List<Overflow> overflows = new ArrayList<>();
            Map<TemplateRegion, TextLayout.Fit> fits = new HashMap<>();
            Map<TemplateRegion, PDFont> fontOf = new HashMap<>();
            for (TemplateRegion r : drawn) {
                if (!isText(r.kind()) || r.box().page() < 0 || r.box().page() >= originalPages) {
                    continue;
                }
                String v = vals.get(r.key());
                if (v == null || v.isBlank()) {
                    continue;
                }
                PageFrame frame = frames.get(r.box().page());
                Rect box = frame.toDisplay(Rect.ofBox(r.box()));
                PDFont font = fonts.choose(r.fontName(), v);
                boolean multi = r.kind() == RegionKind.MULTILINE;
                TextLayout.Fit fit = TextLayout.fit(font, v, box.w(), box.h(), r.fontSize(), minSize,
                        multi ? Math.max(1, r.maxLines()) : 1, multi);
                fontOf.put(r, font);
                fits.put(r, fit);
                if (fit.overflow() && continuation) {
                    overflows.add(new Overflow(r, TextLayout.clean(v), font,
                            Math.max(minSize, r.fontSize() > 0 ? r.fontSize() : 10f)));
                }
            }

            // continuation layout (page numbers are needed for the "(continued on page N)" notes)
            List<ContLine> contLines = new ArrayList<>();
            Map<TemplateRegion, Integer> contPage = new HashMap<>();
            int addedPages = 0;
            PageFrame last = frames.get(originalPages - 1);
            float cw = last.displayWidth();
            float ch = last.displayHeight();
            float band = frames.get(0).displayHeight() * HEADER_BAND;
            if (!overflows.isEmpty()) {
                PDFont headingFont = fonts.bundled("LiberationSans-Bold");
                int page = 0;
                float y = ch - band - 24;
                for (Overflow o : overflows) {
                    float size = o.size();
                    float lineH = size * TextLayout.LINE_GAP;
                    if (y - 14 - lineH < CONT_MARGIN) {
                        page++;
                        y = ch - band - 24;
                    }
                    contPage.put(o.region(), originalPages + page + 1);
                    contLines.add(new ContLine(page, CONT_MARGIN, y, "Continued: " + o.region().label(), headingFont,
                            11f, 0));
                    y -= 18;
                    int rgb = RegionAssembler.parseHex(o.region().color(), 0);
                    for (String line : TextLayout.wrap(o.font(), o.text(), size, cw - 2 * CONT_MARGIN)) {
                        if (y - lineH < CONT_MARGIN) {
                            page++;
                            y = ch - band - 24;
                        }
                        contLines.add(new ContLine(page, CONT_MARGIN, y, line, o.font(), size, rgb));
                        y -= lineH;
                    }
                    y -= 12;
                }
                addedPages = page + 1;
            }

            // pass 2: draw on the template pages
            Map<Integer, PageCanvas> canvases = new TreeMap<>();
            List<String> continued = new ArrayList<>();
            for (TemplateRegion r : drawn) {
                int p = r.box().page();
                if (p < 0 || p >= originalPages) {
                    continue;
                }
                PageFrame frame = frames.get(p);
                PageCanvas canvas = canvases.computeIfAbsent(p, k -> new PageCanvas(doc, doc.getPage(k), frame));
                Rect box = frame.toDisplay(Rect.ofBox(r.box()));
                String v = vals.get(r.key());
                int rgb = RegionAssembler.parseHex(r.color(), 0);
                switch (r.kind()) {
                    case CHECKBOX -> {
                        if (truthy(v)) {
                            canvas.beginClip(box);
                            canvas.checkMark(box, rgb);
                            canvas.endClip();
                        }
                    }
                    case IMAGE, SIGNATURE -> {
                        byte[] bytes = imgs.get(r.key());
                        if (bytes != null && bytes.length > 0) {
                            PDImageXObject img = PDImageXObject.createFromByteArray(doc, bytes, r.key());
                            canvas.beginClip(box);
                            canvas.image(img, box);
                            canvas.endClip();
                        } else if (r.kind() == RegionKind.SIGNATURE && v != null && !v.isBlank()) {
                            PDFont font = fonts.choose("LiberationSerif-Italic", v);
                            TextLayout.Fit fit = TextLayout.fit(font, v, box.w(), box.h(), 0, minSize, 1, false);
                            drawText(canvas, r, box, font, fit, rgb);
                        }
                    }
                    default -> {
                        TextLayout.Fit fit = fits.get(r);
                        if (fit == null) {
                            continue;
                        }
                        PDFont font = fontOf.get(r);
                        Integer target = contPage.get(r);
                        if (target != null) {
                            String note = "(continued on page " + target + ")";
                            PDFont nf = fonts.choose(r.fontName(), note);
                            TextLayout.Fit nfit = TextLayout.fit(nf, note, box.w(), box.h(), r.fontSize(), minSize, 1,
                                    false);
                            drawText(canvas, r, box, nf, nfit, rgb);
                            continued.add(r.key());
                        } else {
                            drawText(canvas, r, box, font, fit, rgb);
                        }
                    }
                }
            }
            for (PageCanvas c : canvases.values()) {
                c.close();
            }

            if (addedPages > 0) {
                headerSource = PdfDocuments.load(templatePdf);
                PDFormXObject header = headerForm(doc, headerSource);
                PageFrame first = PageFrame.of(0, headerSource.getPage(0));
                for (int i = 0; i < addedPages; i++) {
                    PDPage page = new PDPage(new PDRectangle(cw, ch));
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        if (header != null) {
                            cs.saveGraphicsState();
                            cs.addRect(0, ch - band, cw, band);
                            cs.clip();
                            cs.transform(Matrix.getTranslateInstance(0, ch - first.displayHeight()));
                            cs.drawForm(header);
                            cs.restoreGraphicsState();
                        }
                        for (ContLine l : contLines) {
                            if (l.page() != i) {
                                continue;
                            }
                            cs.beginText();
                            cs.setFont(l.font(), l.size());
                            cs.setNonStrokingColor(new Color(l.rgb()));
                            cs.newLineAtOffset(l.x(), l.baseline());
                            cs.showText(PageCanvas.encodable(l.font(), l.text()));
                            cs.endText();
                        }
                    }
                }
            }
            byte[] seed = seed(templatePdf, vals, imgs);
            byte[] out = PdfDocuments.save(doc, seed);
            ExactnessReport report = null;
            if (options != null && options.verify()) {
                List<PdfBox> allowed = new ArrayList<>();
                for (TemplateRegion r : sorted) {
                    allowed.add(r.box());
                }
                report = PdfRendering.verify(templatePdf, out, allowed, 150, originalPages);
            }
            return new FillResult(out, List.copyOf(continued), addedPages, report);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not fill the template.", e);
        } finally {
            PdfDocuments.closeQuietly(doc);
            PdfDocuments.closeQuietly(headerSource);
        }
    }

    static boolean isText(RegionKind k) {
        return k == RegionKind.TEXT || k == RegionKind.MULTILINE || k == RegionKind.DATE || k == RegionKind.NUMBER;
    }

    static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("x") || t.equals("yes") || t.equals("1") || t.equals("on")
                || t.equals("checked") || t.equals("y") || t.equals("✓") || t.equals("✔");
    }

    /** Draws fitted lines, clipped to the box, aligned like the region says. */
    private static void drawText(PageCanvas canvas, TemplateRegion r, Rect box, PDFont font, TextLayout.Fit fit,
                                 int rgb) throws IOException {
        float size = fit.size();
        float[] m = FontResolver.metrics(font);
        float asc = m[0] * size;
        float desc = m[1] * size;
        List<String> lines = fit.lines();
        float first;
        if (lines.size() > 1 || r.kind() == RegionKind.MULTILINE) {
            first = box.y1() - asc - (r.source() == RegionSource.DATA_REMOVED ? 0 : Math.min(1.5f, box.h() * 0.05f));
        } else if (r.source() == RegionSource.DATA_REMOVED) {
            first = box.y0() - desc;
        } else {
            first = box.y0() + (box.h() - (asc - desc)) / 2f - desc;
        }
        canvas.beginClip(box);
        float baseline = first;
        for (String line : lines) {
            float w = FontResolver.width(font, line, size);
            float x = switch (r.align() == null ? Align.LEFT : r.align()) {
                case CENTER -> box.x0() + (box.w() - w) / 2f;
                case RIGHT -> box.x1() - 1f - w;
                default -> box.x0() + (r.source() == RegionSource.DATA_REMOVED ? 0f : 1f);
            };
            canvas.text(font, size, rgb, x, baseline, line);
            baseline -= size * TextLayout.LINE_GAP;
        }
        canvas.endClip();
    }

    // ------------------------------------------------------------------ AcroForm

    /** Fills and flattens the AcroForm fields; returns the regions that still need drawing. */
    private static List<TemplateRegion> fillAcroForm(PDDocument doc, List<TemplateRegion> regions,
                                                     Map<String, String> values) {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form == null) {
            return regions;
        }
        List<TemplateRegion> rest = new ArrayList<>();
        for (TemplateRegion r : regions) {
            if (r.acroFieldName() == null || r.acroFieldName().isBlank()) {
                rest.add(r);
                continue;
            }
            String name = r.acroFieldName();
            int widgetIndex = -1;
            int hash = name.lastIndexOf('#');
            if (hash > 0 && name.substring(hash + 1).matches("\\d+")) {
                widgetIndex = Integer.parseInt(name.substring(hash + 1));
                name = name.substring(0, hash);
            }
            PDField field = form.getField(name);
            String v = values.get(r.key());
            if (field == null) {
                rest.add(r);
                continue;
            }
            try {
                if (field instanceof PDCheckBox cb) {
                    if (truthy(v)) {
                        cb.check();
                    } else {
                        cb.unCheck();
                    }
                } else if (field instanceof PDRadioButton rb) {
                    if (truthy(v) && widgetIndex >= 0 && widgetIndex < rb.getWidgets().size()) {
                        String on = onValue(rb.getWidgets().get(widgetIndex));
                        if (on != null) {
                            rb.setValue(on);
                        }
                    }
                } else if (field instanceof PDTextField tf) {
                    if (v != null && !v.isBlank()) {
                        String clean = TextLayout.clean(v);
                        tf.setValue(tf.isMultiline() ? clean : clean.replace('\n', ' '));
                    }
                } else if (field instanceof PDChoice ch) {
                    if (v != null && !v.isBlank()) {
                        ch.setValue(v);
                    }
                } else {
                    rest.add(r);
                }
            } catch (IOException | RuntimeException e) {
                // the field's own font cannot show the value: draw it ourselves over a cleared field
                log.info("AcroForm field '{}' drawn directly ({})", name, e.getMessage());
                try {
                    if (field instanceof PDTextField tf) {
                        tf.setValue("");
                    }
                } catch (IOException | RuntimeException ignored) {
                    // leave the field as it is
                }
                rest.add(new TemplateRegion(r.key(), r.label(), r.group(), r.kind(), r.box(), r.fontName(),
                        r.fontSize(), r.color(), r.align(), r.maxLines(), null, r.required(), r.sortOrder(),
                        r.source(), r.confidence(), r.sampleValue()));
            }
        }
        try {
            form.flatten();
        } catch (IOException | RuntimeException e) {
            log.warn("AcroForm flatten failed: {}", e.getMessage());
        }
        return rest;
    }

    private static String onValue(PDAnnotationWidget widget) {
        PDAppearanceDictionary ap = widget.getAppearance();
        if (ap == null) {
            return null;
        }
        PDAppearanceEntry normal = ap.getNormalAppearance();
        if (normal == null || !normal.isSubDictionary()) {
            return null;
        }
        for (COSName n : normal.getSubDictionary().keySet()) {
            if (!COSName.Off.equals(n)) {
                return n.getName();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ continuation header

    /** Template page 1 as a form XObject drawn upright (display space). */
    private static PDFormXObject headerForm(PDDocument target, PDDocument source) {
        try {
            PDPage first = source.getPage(0);
            PDFormXObject form = new LayerUtility(target).importPageAsForm(source, first);
            PageFrame frame = PageFrame.of(0, first);
            AffineTransform rawToDisplay = frame.displayToRaw().createInverse();
            form.setMatrix(rawToDisplay);
            form.setBBox(first.getCropBox());
            return form;
        } catch (IOException | NoninvertibleTransformException | RuntimeException e) {
            log.warn("Continuation header could not be copied: {}", e.getMessage());
            return null;
        }
    }

    private static byte[] seed(byte[] template, Map<String, String> values, Map<String, byte[]> images) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(values).forEach((k, v) -> sb.append(k).append('=').append(v).append('\u0000'));
        List<byte[]> parts = new ArrayList<>();
        parts.add(template);
        parts.add(sb.toString().getBytes(StandardCharsets.UTF_8));
        new TreeMap<>(images).forEach((k, v) -> {
            parts.add(k.getBytes(StandardCharsets.UTF_8));
            parts.add(PdfDocuments.sha256(v));
        });
        return PdfDocuments.sha256(parts.toArray(new byte[0][]));
    }
}
