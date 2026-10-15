package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Walks one page's content with PDFBox's own interpreter and records every glyph (with its exact content
 * location, so the editor can cut it), every image placement and the axis-aligned strokes and boxes. All
 * geometry is in box space (cropBox lower-left origin, unrotated).
 */
final class ContentWalker extends PDFGraphicsStreamEngine {

    /** A form XObject invocation reached from the page. */
    record FormUse(List<Integer> path, PDFormXObject form, PDResources parentResources,
                   PDResources effectiveResources) {
    }

    private static final class Ctx {
        final List<Integer> path;
        final boolean editable;
        int op = -1;
        int depth;
        int str = -1;
        int glyph;

        Ctx(List<Integer> path, boolean editable) {
            this.path = path;
            this.editable = editable;
        }
    }

    private final int pageIndex;
    private final float llx;
    private final float lly;
    private final Deque<Ctx> stack = new ArrayDeque<>();

    final List<Glyph> glyphs = new ArrayList<>();
    final List<Shapes.ImagePlacement> images = new ArrayList<>();
    final List<Shapes.Segment> segments = new ArrayList<>();
    final List<Shapes.FilledRect> fills = new ArrayList<>();
    final Map<List<Integer>, FormUse> forms = new LinkedHashMap<>();
    int curveFills;

    private final Map<PDFont, FontInfo> fontInfo = new IdentityHashMap<>();

    // current path, box space
    private final List<float[]> lines = new ArrayList<>();
    private final List<Rect> rects = new ArrayList<>();
    private float[] start;
    private float[] current;
    private boolean pathHasCurve;
    private float curveMinX;
    private float curveMinY;
    private float curveMaxX;
    private float curveMaxY;

    ContentWalker(PDPage page, int pageIndex) {
        super(page);
        this.pageIndex = pageIndex;
        PDRectangle crop = page.getCropBox();
        this.llx = crop.getLowerLeftX();
        this.lly = crop.getLowerLeftY();
    }

    /** Walks the page; content errors are swallowed so analysis degrades instead of failing. */
    ContentWalker walk() throws IOException {
        processPage(getPage());
        return this;
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        stack.push(new Ctx(List.of(), true));
        try {
            super.processPage(page);
        } finally {
            stack.pop();
        }
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        Ctx c = stack.peek();
        if (c != null) {
            if (c.depth == 0) {
                c.op++;
                c.str = -1;
            }
            c.depth++;
        }
        try {
            super.processOperator(operator, operands);
        } finally {
            if (c != null) {
                c.depth--;
            }
        }
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        enterForm(form, () -> super.showForm(form));
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        enterForm(form, () -> super.showTransparencyGroup(form));
    }

    private interface IoRunnable {
        void run() throws IOException;
    }

    private void enterForm(PDFormXObject form, IoRunnable body) throws IOException {
        Ctx parent = stack.peek();
        List<Integer> path = new ArrayList<>(parent == null ? List.of() : parent.path);
        path.add(parent == null ? -1 : parent.op);
        List<Integer> key = List.copyOf(path);
        PDResources parentResources = getResources();
        PDResources effective = form.getResources() != null ? form.getResources() : parentResources;
        forms.putIfAbsent(key, new FormUse(key, form, parentResources, effective));
        stack.push(new Ctx(key, parent == null || parent.editable));
        try {
            body.run();
        } finally {
            stack.pop();
        }
    }

    @Override
    protected void showText(byte[] string) throws IOException {
        Ctx c = stack.peek();
        if (c != null) {
            c.str++;
            c.glyph = 0;
        }
        super.showText(string);
    }

    @Override
    protected void showGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
        Ctx c = stack.peek();
        int glyphOrdinal = c == null ? 0 : c.glyph++;
        ContentLocation loc = c == null ? null
                : new ContentLocation(c.path, c.op, c.str, glyphOrdinal, c.editable);
        FontInfo fi = fontInfo.computeIfAbsent(font, FontInfo::of);
        float adv = font.isVertical() ? 1f : displacement.getX();
        if (font instanceof PDType3Font) {
            adv = displacement.getX();
        }
        Rect layout = transformRect(trm, 0, fi.descent, Math.max(adv, 0.001f), fi.ascent);
        Rect ink = null;
        Rectangle2D inkEm = fi.inkBounds(font, code);
        if (inkEm != null && inkEm.getWidth() > 0 && inkEm.getHeight() > 0) {
            ink = transformRect(trm, (float) inkEm.getMinX(), (float) inkEm.getMinY(),
                    (float) inkEm.getMaxX(), (float) inkEm.getMaxY());
        }
        float ox = trm.getTranslateX() - llx;
        float oy = trm.getTranslateY() - lly;
        float ax = trm.getValue(0, 0);
        float ay = trm.getValue(0, 1);
        float len = (float) Math.hypot(ax, ay);
        float dx = len == 0 ? 1 : ax / len;
        float dy = len == 0 ? 0 : ay / len;
        float size = (float) Math.hypot(trm.getValue(1, 0), trm.getValue(1, 1));
        PDGraphicsState gs = getGraphicsState();
        RenderingMode mode = gs.getTextState().getRenderingMode();
        int rgb = colour(mode.isFill() || !mode.isStroke() ? gs.getNonStrokingColor() : gs.getStrokingColor());
        String text = null;
        try {
            text = font.toUnicode(code);
        } catch (RuntimeException ignored) {
            // garbled text is handled as unknown
        }
        if (text == null || text.isEmpty()) {
            text = "�";
        }
        boolean bold = fi.bold || mode == RenderingMode.FILL_STROKE;
        Glyph g = new Glyph(pageIndex, glyphs.size(), text, font, fi.name, size, rgb, bold, fi.italic,
                layout, ink, ox, oy, dx, dy, code, loc, mode.intValue(), false);
        var ts = gs.getTextState();
        g.tfs = ts.getFontSize();
        g.tc = ts.getCharacterSpacing();
        g.tw = ts.getWordSpacing();
        g.th = ts.getHorizontalScaling() / 100f;
        glyphs.add(g);
    }

    private Rect transformRect(Matrix m, float x0, float y0, float x1, float y1) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float[][] pts = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (float[] p : pts) {
            Point2D.Float t = m.transformPoint(p[0], p[1]);
            minX = Math.min(minX, t.x);
            minY = Math.min(minY, t.y);
            maxX = Math.max(maxX, t.x);
            maxY = Math.max(maxY, t.y);
        }
        return new Rect(minX - llx, minY - lly, maxX - llx, maxY - lly);
    }

    static int colour(PDColor color) {
        if (color == null) {
            return 0;
        }
        try {
            return color.toRGB() & 0xFFFFFF;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ images

    @Override
    public void drawImage(PDImage pdImage) {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        Rect r = transformRect(ctm, 0, 0, 1, 1);
        Ctx c = stack.peek();
        ContentLocation loc = c == null ? null : new ContentLocation(c.path, c.op, -1, -1, c.editable);
        images.add(new Shapes.ImagePlacement(r, loc));
    }

    // ------------------------------------------------------------------ paths

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        float minX = (float) Math.min(Math.min(p0.getX(), p1.getX()), Math.min(p2.getX(), p3.getX()));
        float maxX = (float) Math.max(Math.max(p0.getX(), p1.getX()), Math.max(p2.getX(), p3.getX()));
        float minY = (float) Math.min(Math.min(p0.getY(), p1.getY()), Math.min(p2.getY(), p3.getY()));
        float maxY = (float) Math.max(Math.max(p0.getY(), p1.getY()), Math.max(p2.getY(), p3.getY()));
        boolean axisAligned = (Math.abs(p0.getX() - p1.getX()) < 0.01 || Math.abs(p0.getY() - p1.getY()) < 0.01);
        if (axisAligned) {
            rects.add(new Rect(minX - llx, minY - lly, maxX - llx, maxY - lly));
        }
        start = new float[] {(float) p0.getX() - llx, (float) p0.getY() - lly};
        current = start;
    }

    @Override
    public void clip(int windingRule) {
        // a clip path is not drawn
    }

    @Override
    public void moveTo(float x, float y) {
        start = new float[] {x - llx, y - lly};
        current = start;
    }

    @Override
    public void lineTo(float x, float y) {
        float[] p = {x - llx, y - lly};
        if (current != null) {
            lines.add(new float[] {current[0], current[1], p[0], p[1]});
        }
        current = p;
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        float[] p = {x3 - llx, y3 - lly};
        if (!pathHasCurve) {
            curveMinX = Float.MAX_VALUE;
            curveMinY = Float.MAX_VALUE;
            curveMaxX = -Float.MAX_VALUE;
            curveMaxY = -Float.MAX_VALUE;
        }
        pathHasCurve = true;
        for (float[] q : new float[][] {{x1 - llx, y1 - lly}, {x2 - llx, y2 - lly}, p}) {
            curveMinX = Math.min(curveMinX, q[0]);
            curveMinY = Math.min(curveMinY, q[1]);
            curveMaxX = Math.max(curveMaxX, q[0]);
            curveMaxY = Math.max(curveMaxY, q[1]);
        }
        current = p;
    }

    @Override
    public Point2D getCurrentPoint() {
        return current == null ? new Point2D.Float(0, 0) : new Point2D.Float(current[0] + llx, current[1] + lly);
    }

    @Override
    public void closePath() {
        if (current != null && start != null && (current[0] != start[0] || current[1] != start[1])) {
            lines.add(new float[] {current[0], current[1], start[0], start[1]});
        }
        current = start;
    }

    @Override
    public void endPath() {
        resetPath();
    }

    @Override
    public void strokePath() {
        emitStroke();
        resetPath();
    }

    @Override
    public void fillPath(int windingRule) {
        emitFill();
        resetPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        emitFill();
        emitStroke();
        resetPath();
    }

    @Override
    public void shadingFill(COSName shadingName) {
        // shadings are background, not layout
    }

    private void emitStroke() {
        for (float[] l : lines) {
            addSegment(l[0], l[1], l[2], l[3]);
        }
        for (Rect r : rects) {
            addSegment(r.x0(), r.y0(), r.x1(), r.y0());
            addSegment(r.x0(), r.y1(), r.x1(), r.y1());
            addSegment(r.x0(), r.y0(), r.x0(), r.y1());
            addSegment(r.x1(), r.y0(), r.x1(), r.y1());
        }
    }

    private void emitFill() {
        int rgb = colour(getGraphicsState().getNonStrokingColor());
        List<Rect> shapes = new ArrayList<>(rects);
        if (!lines.isEmpty() && !pathHasCurve) {
            // a polygon drawn with lines: use its bounds when it is a rectangle-ish shape
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            boolean axis = true;
            for (float[] l : lines) {
                minX = Math.min(minX, Math.min(l[0], l[2]));
                maxX = Math.max(maxX, Math.max(l[0], l[2]));
                minY = Math.min(minY, Math.min(l[1], l[3]));
                maxY = Math.max(maxY, Math.max(l[1], l[3]));
                if (Math.abs(l[0] - l[2]) > 0.5 && Math.abs(l[1] - l[3]) > 0.5) {
                    axis = false;
                }
            }
            if (axis) {
                shapes.add(new Rect(minX, minY, maxX, maxY));
            }
        }
        if (pathHasCurve) {
            curveFills++;
        }
        for (Rect r : shapes) {
            if (r.h() <= 2.0f && r.w() >= 4) {
                addSegment(r.x0(), r.cy(), r.x1(), r.cy());
            } else if (r.w() <= 2.0f && r.h() >= 4) {
                addSegment(r.cx(), r.y0(), r.cx(), r.y1());
            } else if (r.w() > 2 && r.h() > 2) {
                fills.add(new Shapes.FilledRect(r, rgb));
            }
        }
    }

    private void addSegment(float x0, float y0, float x1, float y1) {
        if (Math.abs(y0 - y1) < 0.6f && Math.abs(x1 - x0) >= 2f) {
            segments.add(new Shapes.Segment(true, (y0 + y1) / 2f, Math.min(x0, x1), Math.max(x0, x1)));
        } else if (Math.abs(x0 - x1) < 0.6f && Math.abs(y1 - y0) >= 2f) {
            segments.add(new Shapes.Segment(false, (x0 + x1) / 2f, Math.min(y0, y1), Math.max(y0, y1)));
        }
    }

    private void resetPath() {
        lines.clear();
        rects.clear();
        start = null;
        current = null;
        pathHasCurve = false;
    }

    // ------------------------------------------------------------------ fonts

    /** Cached metrics of one font. */
    static final class FontInfo {
        final String name;
        final float ascent;
        final float descent;
        final boolean bold;
        final boolean italic;
        private final Map<Integer, Rectangle2D> ink = new HashMap<>();

        private FontInfo(String name, float ascent, float descent, boolean bold, boolean italic) {
            this.name = name;
            this.ascent = ascent;
            this.descent = descent;
            this.bold = bold;
            this.italic = italic;
        }

        static FontInfo of(PDFont font) {
            String name = font.getName() == null ? "Unknown" : font.getName();
            float ascent = 0;
            float descent = 0;
            boolean bold = false;
            boolean italic = false;
            PDFontDescriptor fd = font.getFontDescriptor();
            if (fd != null) {
                ascent = fd.getAscent() / 1000f;
                descent = fd.getDescent() / 1000f;
                bold = fd.isForceBold() || fd.getFontWeight() >= 600;
                italic = fd.isItalic() || fd.getItalicAngle() != 0;
                if (ascent <= 0.2f && fd.getFontBoundingBox() != null) {
                    ascent = fd.getFontBoundingBox().getUpperRightY() / 1000f;
                }
            }
            if (font instanceof PDType3Font) {
                ascent = 0.8f;
                descent = -0.2f;
            }
            if (ascent <= 0.2f || ascent > 1.5f) {
                ascent = 0.8f;
            }
            if (descent >= 0 || descent < -0.6f) {
                descent = -0.2f;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("bold") || lower.contains("black") || lower.contains("heavy")
                    || lower.contains("semibold") || lower.contains("demi")) {
                bold = true;
            }
            if (lower.contains("italic") || lower.contains("oblique")) {
                italic = true;
            }
            return new FontInfo(name, ascent, descent, bold, italic);
        }

        /** Ink bounds in text space units (1 = font size), or null. */
        Rectangle2D inkBounds(PDFont font, int code) {
            if (!(font instanceof PDVectorFont vf) || font instanceof PDType3Font) {
                return null;
            }
            return ink.computeIfAbsent(code, c -> {
                try {
                    GeneralPath path = vf.getNormalizedPath(c);
                    if (path == null) {
                        return null;
                    }
                    // same transform the renderer applies (PageDrawer: TRM x font matrix)
                    java.awt.Shape shape = font.getFontMatrix().createAffineTransform().createTransformedShape(path);
                    Rectangle2D b = shape.getBounds2D();
                    if (b.isEmpty()) {
                        return null;
                    }
                    return b;
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            });
        }
    }
}
