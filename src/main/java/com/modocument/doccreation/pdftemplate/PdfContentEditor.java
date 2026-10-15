package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cuts glyphs and images out of page content by rewriting the content stream, keeping every other glyph at
 * exactly the same position: each removed glyph is replaced by a TJ displacement that PDFBox computes with
 * the very same float operations as the glyph advance it replaces. Form XObjects are edited copy-on-write.
 * Areas with nothing to cut (scans, outlined text) are painted over with the surrounding colour.
 */
final class PdfContentEditor {

    private static final Logger log = LoggerFactory.getLogger(PdfContentEditor.class);
    private static final Set<String> TEXT_SHOW = Set.of("Tj", "TJ", "'", "\"");

    /** What a removal pass did on one page. */
    record PageRemoval(List<Glyph> removedGlyphs, int removedImages, List<Rect> paintedBoxes) {
    }

    private static final class OpPlan {
        final Map<Integer, Set<Integer>> remove = new HashMap<>();
        final Map<Integer, List<Glyph>> glyphs = new HashMap<>();
    }

    private static final class StreamPlan {
        final Map<Integer, OpPlan> ops = new HashMap<>();
        final Set<Integer> drop = new HashSet<>();
        final Map<Integer, COSName> renames = new HashMap<>();
    }

    private PdfContentEditor() {
    }

    /**
     * Removes, on one page, the glyphs whose ink centre lies in a box and the images lying entirely in a box.
     * Boxes are in box space. When {@code paint} is set for a box and nothing visible was cut in it, it is
     * painted with the colour sampled around it (scans, text drawn as outlines).
     */
    static PageRemoval removeInBoxes(PDDocument doc, int pageIndex, List<Rect> boxes, List<Boolean> paint,
                                     PageCanvas canvas) throws IOException {
        PDPage page = doc.getPage(pageIndex);
        ContentWalker walker = new ContentWalker(page, pageIndex);
        try {
            walker.walk();
        } catch (IOException | RuntimeException e) {
            log.warn("Page {} content could not be read for editing: {}", pageIndex + 1, e.getMessage());
        }
        Map<List<Integer>, Map<Integer, List<Glyph>>> byOp = new HashMap<>();
        for (Glyph g : walker.glyphs) {
            if (g.loc != null) {
                byOp.computeIfAbsent(g.loc.formPath(), k -> new HashMap<>())
                        .computeIfAbsent(g.loc.opIndex(), k -> new ArrayList<>()).add(g);
            }
        }
        List<Glyph> removed = new ArrayList<>();
        boolean[] visibleCut = new boolean[boxes.size()];
        Map<List<Integer>, StreamPlan> plans = new LinkedHashMap<>();
        for (Glyph g : walker.glyphs) {
            if (g.loc == null || !g.loc.editable() || g.renderMode >= 4) {
                continue;
            }
            float[] c = g.centre();
            int hit = firstContaining(boxes, c[0], c[1]);
            if (hit < 0) {
                continue;
            }
            removed.add(g);
            if (g.renderMode != 3) {
                visibleCut[hit] = true;
            }
            StreamPlan sp = plans.computeIfAbsent(g.loc.formPath(), k -> new StreamPlan());
            OpPlan op = sp.ops.computeIfAbsent(g.loc.opIndex(), k -> {
                OpPlan p = new OpPlan();
                for (Glyph any : byOp.get(g.loc.formPath()).get(g.loc.opIndex())) {
                    p.glyphs.computeIfAbsent(any.loc.stringOrdinal(), s -> new ArrayList<>()).add(any);
                }
                return p;
            });
            op.remove.computeIfAbsent(g.loc.stringOrdinal(), k -> new HashSet<>()).add(g.loc.glyphOrdinal());
        }
        int removedImages = 0;
        for (Shapes.ImagePlacement img : walker.images) {
            if (img.loc() == null || !img.loc().editable()) {
                continue;
            }
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).containsRect(img.rect(), 1f)) {
                    plans.computeIfAbsent(img.loc().formPath(), k -> new StreamPlan()).drop.add(img.loc().opIndex());
                    visibleCut[i] = true;
                    removedImages++;
                    break;
                }
            }
        }
        // paint-over colours are sampled from the page as it is before the rewrite
        List<Rect> painted = new ArrayList<>();
        List<Integer> toPaint = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (!visibleCut[i] && i < paint.size() && Boolean.TRUE.equals(paint.get(i))) {
                toPaint.add(i);
            }
        }
        Map<Integer, Integer> paintColour = new HashMap<>();
        if (!toPaint.isEmpty()) {
            BufferedImage img = renderForSampling(doc, pageIndex);
            PageFrame frame = canvas.frame();
            for (int i : toPaint) {
                paintColour.put(i, sampleBorder(img, frame, boxes.get(i), 100f / 72f));
            }
        }
        if (!plans.isEmpty()) {
            // every ancestor of an edited form must be rewritten too (to point at the copy)
            for (List<Integer> path : new ArrayList<>(plans.keySet())) {
                for (int n = path.size() - 1; n >= 0; n--) {
                    plans.computeIfAbsent(List.copyOf(path.subList(0, n)), k -> new StreamPlan());
                }
            }
            new Rewriter(doc, page, walker, plans).rewrite(List.of());
        }
        for (int i : toPaint) {
            Rect box = boxes.get(i);
            canvas.fillRect(canvas.frame().toDisplay(box), paintColour.get(i));
            painted.add(box);
        }
        return new PageRemoval(removed, removedImages, painted);
    }

    private static int firstContaining(List<Rect> boxes, float x, float y) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ rewriting

    private static final class Rewriter {
        private final PDDocument doc;
        private final PDPage page;
        private final ContentWalker walker;
        private final Map<List<Integer>, StreamPlan> plans;
        private int nameCounter;

        Rewriter(PDDocument doc, PDPage page, ContentWalker walker, Map<List<Integer>, StreamPlan> plans) {
            this.doc = doc;
            this.page = page;
            this.walker = walker;
            this.plans = plans;
        }

        /** Rewrites the stream at {@code path}; returns the new form for a form path, null for the page. */
        PDFormXObject rewrite(List<Integer> path) throws IOException {
            StreamPlan plan = plans.get(path);
            COSDictionary resourcesCopy = null;
            List<List<Integer>> children = new ArrayList<>();
            for (List<Integer> p : plans.keySet()) {
                if (p.size() == path.size() + 1 && p.subList(0, path.size()).equals(path)) {
                    children.add(p);
                }
            }
            children.sort(Comparator.comparing(p -> p.get(p.size() - 1)));
            for (List<Integer> child : children) {
                PDFormXObject copy = rewrite(child);
                if (copy == null) {
                    continue;
                }
                if (resourcesCopy == null) {
                    resourcesCopy = copyResources(path);
                }
                COSDictionary xobjects = resourcesCopy.getCOSDictionary(COSName.XOBJECT);
                COSName name;
                do {
                    name = COSName.getPDFName("MoDocE" + (++nameCounter));
                } while (xobjects.containsKey(name));
                xobjects.setItem(name, copy.getCOSObject());
                plan.renames.put(child.get(child.size() - 1), name);
            }
            List<Object> tokens;
            ContentWalker.FormUse use = path.isEmpty() ? null : walker.forms.get(path);
            if (path.isEmpty()) {
                tokens = new PDFStreamParser(page).parse();
            } else {
                if (use == null) {
                    return null;
                }
                tokens = new PDFStreamParser(use.form()).parse();
            }
            List<Object> out = apply(tokens, plan);
            if (path.isEmpty()) {
                PDStream stream = new PDStream(doc);
                try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                    new ContentStreamWriter(os).writeTokens(out);
                }
                page.setContents(stream);
                if (resourcesCopy != null) {
                    page.setResources(new PDResources(resourcesCopy));
                }
                return null;
            }
            COSStream original = use.form().getCOSObject();
            COSStream copy = doc.getDocument().createCOSStream();
            for (Map.Entry<COSName, COSBase> e : original.entrySet()) {
                COSName k = e.getKey();
                if (COSName.LENGTH.equals(k) || COSName.FILTER.equals(k) || COSName.DECODE_PARMS.equals(k)
                        || COSName.DL.equals(k) || COSName.F.equals(k) || COSName.F_FILTER.equals(k)
                        || COSName.F_DECODE_PARMS.equals(k)) {
                    continue;
                }
                copy.setItem(k, e.getValue());
            }
            if (resourcesCopy != null) {
                copy.setItem(COSName.RESOURCES, resourcesCopy);
            } else if (original.getDictionaryObject(COSName.RESOURCES) == null && use.effectiveResources() != null) {
                copy.setItem(COSName.RESOURCES, use.effectiveResources().getCOSObject());
            }
            try (OutputStream os = copy.createOutputStream(COSName.FLATE_DECODE)) {
                new ContentStreamWriter(os).writeTokens(out);
            }
            return new PDFormXObject(copy);
        }

        private COSDictionary copyResources(List<Integer> path) {
            PDResources res;
            if (path.isEmpty()) {
                res = page.getResources();
            } else {
                ContentWalker.FormUse use = walker.forms.get(path);
                res = use == null ? null : use.effectiveResources();
            }
            COSDictionary copy = res == null ? new COSDictionary() : new COSDictionary(res.getCOSObject());
            COSDictionary xo = copy.getCOSDictionary(COSName.XOBJECT);
            copy.setItem(COSName.XOBJECT, xo == null ? new COSDictionary() : new COSDictionary(xo));
            return copy;
        }

        private List<Object> apply(List<Object> tokens, StreamPlan plan) throws IOException {
            List<Object> out = new ArrayList<>(tokens.size());
            List<COSBase> operands = new ArrayList<>();
            int opIndex = -1;
            for (Object token : tokens) {
                if (!(token instanceof Operator op)) {
                    operands.add((COSBase) token);
                    continue;
                }
                opIndex++;
                if (plan.drop.contains(opIndex)) {
                    operands.clear();
                    continue;
                }
                COSName rename = plan.renames.get(opIndex);
                if (rename != null && "Do".equals(op.getName()) && !operands.isEmpty()) {
                    operands.set(operands.size() - 1, rename);
                }
                OpPlan textPlan = plan.ops.get(opIndex);
                List<Object> replaced = textPlan == null || !TEXT_SHOW.contains(op.getName())
                        ? null : rewriteText(op, operands, textPlan);
                if (replaced != null) {
                    out.addAll(replaced);
                } else {
                    out.addAll(operands);
                    out.add(op);
                }
                operands.clear();
            }
            out.addAll(operands);
            return out;
        }
    }

    /** The replacement tokens for one text-showing operator, or null to keep it as it is. */
    static List<Object> rewriteText(Operator op, List<COSBase> operands, OpPlan plan) throws IOException {
        List<Object> out = new ArrayList<>();
        COSArray result = new COSArray();
        switch (op.getName()) {
            case "Tj", "'" -> {
                if (operands.isEmpty() || !(operands.get(0) instanceof COSString s)) {
                    return null;
                }
                if (!splitInto(result, s, 0, plan)) {
                    return null;
                }
                if ("'".equals(op.getName())) {
                    out.add(Operator.getOperator("T*"));
                }
            }
            case "\"" -> {
                if (operands.size() < 3 || !(operands.get(2) instanceof COSString s)) {
                    return null;
                }
                if (!splitInto(result, s, 0, plan)) {
                    return null;
                }
                out.add(operands.get(0));
                out.add(Operator.getOperator("Tw"));
                out.add(operands.get(1));
                out.add(Operator.getOperator("Tc"));
                out.add(Operator.getOperator("T*"));
            }
            case "TJ" -> {
                if (operands.isEmpty() || !(operands.get(0) instanceof COSArray array)) {
                    return null;
                }
                int k = -1;
                for (int i = 0; i < array.size(); i++) {
                    COSBase el = array.getObject(i);
                    if (el instanceof COSString s) {
                        k++;
                        if (!splitInto(result, s, k, plan)) {
                            return null;
                        }
                    } else {
                        result.add(el);
                    }
                }
            }
            default -> {
                return null;
            }
        }
        out.add(result);
        out.add(Operator.getOperator("TJ"));
        return out;
    }

    /** Appends the string's kept glyph runs and, for each removed glyph, its exact displacement. */
    private static boolean splitInto(COSArray result, COSString s, int stringOrdinal, OpPlan plan) throws IOException {
        Set<Integer> remove = plan.remove.get(stringOrdinal);
        if (remove == null || remove.isEmpty()) {
            result.add(s);
            return true;
        }
        List<Glyph> glyphs = new ArrayList<>(plan.glyphs.getOrDefault(stringOrdinal, List.of()));
        glyphs.sort(Comparator.comparingInt(g -> g.loc.glyphOrdinal()));
        if (glyphs.isEmpty()) {
            return false;
        }
        PDFont font = glyphs.get(0).font;
        byte[] bytes = s.getBytes();
        InputStream in = new ByteArrayInputStream(bytes);
        int pos = 0;
        int index = 0;
        int keptFrom = -1;
        List<COSBase> parts = new ArrayList<>();
        while (in.available() > 0) {
            int before = in.available();
            int code = font.readCode(in);
            int len = before - in.available();
            if (index >= glyphs.size() || glyphs.get(index).code != code) {
                log.warn("Glyph sequence mismatch while editing; operator left unchanged");
                return false;
            }
            Glyph g = glyphs.get(index);
            if (remove.contains(index)) {
                if (keptFrom >= 0) {
                    parts.add(slice(s, bytes, keptFrom, pos));
                    keptFrom = -1;
                }
                parts.add(displacement(g, code, len));
            } else if (keptFrom < 0) {
                keptFrom = pos;
            }
            pos += len;
            index++;
        }
        if (keptFrom >= 0) {
            parts.add(slice(s, bytes, keptFrom, pos));
        }
        for (COSBase p : parts) {
            result.add(p);
        }
        return true;
    }

    private static COSString slice(COSString original, byte[] bytes, int from, int to) {
        byte[] part = new byte[to - from];
        System.arraycopy(bytes, from, part, 0, part.length);
        return new COSString(part, original.getForceHexForm());
    }

    /**
     * The TJ number that moves the text position exactly like the removed glyph did. With no character or
     * word spacing it is minus the glyph width, which PDFBox turns into the identical float advance.
     */
    static COSNumber displacement(Glyph g, int code, int codeLength) throws IOException {
        PDFont font = g.font;
        float raw;
        if (font instanceof PDType3Font) {
            raw = font.getDisplacement(code).getX() * 1000f;
        } else if (font.isVertical() && font instanceof PDType0Font t0 && t0.getDescendantFont() instanceof PDCIDFont cid) {
            raw = cid.getVerticalDisplacementVectorY(code);
        } else {
            raw = font.getWidth(code);
        }
        float wordSpacing = codeLength == 1 && code == 32 ? g.tw : 0f;
        float tj;
        if ((g.tc != 0f || wordSpacing != 0f) && g.tfs != 0f && !(font instanceof PDType3Font)) {
            tj = -((raw / 1000f * g.tfs + g.tc + wordSpacing) / g.tfs * 1000f);
        } else {
            tj = -raw;
        }
        if (tj == Math.rint(tj) && Math.abs(tj) < 1e9) {
            return COSInteger.get((long) tj);
        }
        return new COSFloat(tj);
    }

    // ------------------------------------------------------------------ paint-over

    static BufferedImage renderForSampling(PDDocument doc, int pageIndex) throws IOException {
        return new PDFRenderer(doc).renderImageWithDPI(pageIndex, 100, ImageType.RGB);
    }

    /** The most frequent colour in a thin ring just outside the box (white when none). */
    static int sampleBorder(BufferedImage img, PageFrame frame, Rect box, float scale) {
        AffineTransform t = frame.boxToPixels(scale);
        Point2D a = t.transform(new Point2D.Float(box.x0(), box.y0()), null);
        Point2D b = t.transform(new Point2D.Float(box.x1(), box.y1()), null);
        int x0 = (int) Math.floor(Math.min(a.getX(), b.getX()));
        int x1 = (int) Math.ceil(Math.max(a.getX(), b.getX()));
        int y0 = (int) Math.floor(Math.min(a.getY(), b.getY()));
        int y1 = (int) Math.ceil(Math.max(a.getY(), b.getY()));
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int ring = 2; ring <= 4; ring++) {
            for (int x = x0 - ring; x <= x1 + ring; x++) {
                count(img, x, y0 - ring, counts);
                count(img, x, y1 + ring, counts);
            }
            for (int y = y0 - ring; y <= y1 + ring; y++) {
                count(img, x0 - ring, y, counts);
                count(img, x1 + ring, y, counts);
            }
        }
        int best = 0xFFFFFF;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static void count(BufferedImage img, int x, int y, Map<Integer, Integer> counts) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return;
        }
        counts.merge(img.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ AcroForm

    /** Clears every field value and regenerates (or drops) its appearance. Returns the number of fields cleared. */
    static int clearAcroForm(PDDocument doc) {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form == null) {
            return 0;
        }
        int cleared = 0;
        for (PDField field : form.getFieldTree()) {
            if (!(field instanceof PDTerminalField)) {
                continue;
            }
            try {
                if (field instanceof PDCheckBox cb) {
                    cb.unCheck();
                } else if (field instanceof PDRadioButton rb) {
                    rb.getCOSObject().setItem(COSName.V, COSName.Off);
                    for (PDAnnotationWidget w : rb.getWidgets()) {
                        w.setAppearanceState("Off");
                    }
                } else if (field instanceof PDTextField tf) {
                    tf.setValue("");
                } else if (field instanceof PDChoice ch) {
                    ch.setValue(List.of());
                } else if (field instanceof PDSignatureField sig) {
                    sig.getCOSObject().removeItem(COSName.V);
                    for (PDAnnotationWidget w : sig.getWidgets()) {
                        w.setAppearance(null);
                    }
                } else {
                    continue;
                }
                cleared++;
            } catch (IOException | RuntimeException e) {
                field.getCOSObject().removeItem(COSName.V);
                for (PDAnnotationWidget w : ((PDTerminalField) field).getWidgets()) {
                    w.setAppearance(null);
                }
                cleared++;
            }
        }
        return cleared;
    }
}
