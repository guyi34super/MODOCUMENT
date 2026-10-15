package com.modocument.doccreation.pdftemplate;

import java.util.ArrayList;
import java.util.List;

/** Glyphs on one baseline, close together, same font and colour, in display space. */
final class TextRun {

    final String id;
    final int page;
    final List<Glyph> glyphs;
    final String text;
    /** For each char of {@link #text}: the glyph index, or -1 for an inserted space. */
    final int[] charToGlyph;
    final Rect box;
    final Rect ink;
    final float baseline;
    final float size;
    final String fontName;
    final int rgb;
    final boolean bold;
    final boolean italic;

    TextRun(String id, List<Glyph> glyphs) {
        this.id = id;
        this.glyphs = List.copyOf(glyphs);
        Glyph first = glyphs.get(0);
        this.page = first.page;
        StringBuilder sb = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        Rect b = null;
        Rect k = null;
        float maxSize = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            if (i > 0) {
                Glyph p = glyphs.get(i - 1);
                float gap = g.dbox.x0() - p.dbox.x1();
                if (gap > 0.2f * Math.max(g.size, p.size) && !g.isSpace() && !p.isSpace()) {
                    sb.append(' ');
                    map.add(-1);
                }
            }
            for (int c = 0; c < g.text.length(); c++) {
                sb.append(g.text.charAt(c));
                map.add(i);
            }
            b = b == null ? g.dbox : b.union(g.dbox);
            Rect gi = g.dink != null ? g.dink : g.dbox;
            if (!g.isSpace()) {
                k = k == null ? gi : k.union(gi);
            }
            maxSize = Math.max(maxSize, g.size);
        }
        this.text = sb.toString();
        this.charToGlyph = map.stream().mapToInt(Integer::intValue).toArray();
        this.box = b;
        this.ink = k == null ? b : k;
        this.baseline = first.dOriginY;
        this.size = maxSize;
        this.fontName = first.fontName;
        this.rgb = first.rgb;
        this.bold = first.bold;
        this.italic = first.italic;
    }

    /** Glyphs covering text[from, to), spaces at both ends dropped. */
    List<Glyph> glyphsFor(int from, int to) {
        List<Glyph> out = new ArrayList<>();
        int last = -1;
        for (int c = Math.max(0, from); c < Math.min(to, charToGlyph.length); c++) {
            int gi = charToGlyph[c];
            if (gi >= 0 && gi != last) {
                out.add(glyphs.get(gi));
                last = gi;
            }
        }
        while (!out.isEmpty() && out.get(0).isSpace()) {
            out.remove(0);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isSpace()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    float x0() {
        return box.x0();
    }

    float x1() {
        return box.x1();
    }

    String trimmed() {
        return text.strip();
    }

    @Override
    public String toString() {
        return id + "'" + text + "'@" + box;
    }

    static Rect layoutBox(List<Glyph> glyphs) {
        Rect r = null;
        for (Glyph g : glyphs) {
            r = r == null ? g.dbox : r.union(g.dbox);
        }
        return r;
    }

    static Rect inkBox(List<Glyph> glyphs) {
        Rect r = null;
        for (Glyph g : glyphs) {
            if (g.isSpace()) {
                continue;
            }
            Rect i = g.dink != null ? g.dink : g.dbox;
            r = r == null ? i : r.union(i);
        }
        return r == null ? layoutBox(glyphs) : r;
    }

    static String textOf(List<Glyph> glyphs) {
        StringBuilder sb = new StringBuilder();
        Glyph p = null;
        for (Glyph g : glyphs) {
            if (p != null && Math.abs(g.dOriginY - p.dOriginY) > 0.5f * Math.max(g.size, p.size)) {
                sb.append('\n');
            } else if (p != null && g.dbox.x0() - p.dbox.x1() > 0.2f * Math.max(g.size, p.size)
                    && !g.isSpace() && !p.isSpace()) {
                sb.append(' ');
            }
            sb.append(g.text);
            p = g;
        }
        return sb.toString().replaceAll("[ \\t]+\\n", "\n").strip();
    }
}
