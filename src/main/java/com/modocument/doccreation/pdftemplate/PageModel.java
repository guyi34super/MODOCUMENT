package com.modocument.doccreation.pdftemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Everything the detectors know about one page, in display space (text reads upright, y up). */
final class PageModel {

    /** An AcroForm widget. */
    record Widget(int page, Rect rect, String fieldName, String partialName, String alternateName, String type,
                  String value, float fontSize, String fontName, int rgb, boolean required, Align align,
                  boolean multiline) {
    }

    /** A rectangular cell from the ruling lines (or a stand-alone box). */
    record Cell(Rect rect, int table, int row, int col) {
    }

    final PageFrame frame;
    final List<Glyph> glyphs = new ArrayList<>();
    final List<TextRun> runs = new ArrayList<>();
    final List<Shapes.Segment> segments = new ArrayList<>();
    final List<Shapes.FilledRect> fills = new ArrayList<>();
    final List<Shapes.ImagePlacement> images = new ArrayList<>();
    final List<Widget> widgets = new ArrayList<>();
    final List<Cell> cells = new ArrayList<>();
    final List<Shapes.Segment> freeLines = new ArrayList<>();
    boolean ocrUsed;
    boolean needsOcr;
    float bodySize = 10f;
    /** Lines of runs (same baseline), top to bottom, each sorted left to right. */
    final List<List<TextRun>> lines = new ArrayList<>();

    PageModel(PageFrame frame) {
        this.frame = frame;
    }

    int index() {
        return frame.index;
    }

    float width() {
        return frame.displayWidth();
    }

    float height() {
        return frame.displayHeight();
    }

    /** The smallest cell containing the point, or null. */
    Cell cellAt(float x, float y) {
        if (cells.isEmpty()) {
            return null;
        }
        long key = ((long) Float.floatToIntBits(x) << 32) | (Float.floatToIntBits(y) & 0xFFFFFFFFL);
        if (cellAtCache.containsKey(key)) {
            return cellAtCache.get(key);
        }
        Cell found = computeCellAt(x, y);
        cellAtCache.put(key, found);
        return found;
    }

    private Cell computeCellAt(float x, float y) {
        Cell best = null;
        for (Cell c : cells) {
            if (c.rect().contains(x, y) && (best == null || c.rect().area() < best.rect().area())) {
                best = c;
            }
        }
        return best;
    }

    private final java.util.Map<Rect, List<TextRun>> runsInCache = new java.util.HashMap<>();
    private final java.util.Map<Long, Cell> cellAtCache = new java.util.HashMap<>();

    List<TextRun> runsIn(Rect r) {
        return runsInCache.computeIfAbsent(r, this::computeRunsIn);
    }

    private List<TextRun> computeRunsIn(Rect r) {
        List<TextRun> out = new ArrayList<>();
        for (TextRun run : runs) {
            if (r.contains(run.box.cx(), run.box.cy())) {
                out.add(run);
            }
        }
        return out;
    }

    boolean hasGlyphCentreIn(Rect r) {
        for (Glyph g : glyphs) {
            if (!g.isSpace() && g.dbox != null && r.contains(g.dbox.cx(), g.dbox.cy())) {
                return true;
            }
        }
        return false;
    }

    /** The line a run belongs to. */
    List<TextRun> lineOf(TextRun run) {
        for (List<TextRun> line : lines) {
            if (line.contains(run)) {
                return line;
            }
        }
        return List.of(run);
    }

    void buildLines() {
        lines.clear();
        List<TextRun> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparingDouble((TextRun r) -> -r.baseline).thenComparingDouble(r -> r.box.x0()));
        for (TextRun r : sorted) {
            List<TextRun> target = null;
            for (List<TextRun> line : lines) {
                TextRun ref = line.get(0);
                float tol = 0.35f * Math.max(ref.size, r.size);
                if (Math.abs(ref.baseline - r.baseline) <= tol) {
                    target = line;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                lines.add(target);
            }
            target.add(r);
        }
        for (List<TextRun> line : lines) {
            line.sort(Comparator.comparingDouble(r -> r.box.x0()));
        }
        lines.sort(Comparator.comparingDouble(l -> -l.get(0).baseline));
    }
}
