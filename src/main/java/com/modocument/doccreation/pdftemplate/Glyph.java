package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * One drawn glyph (or one OCR word, which has no content location). Geometry is kept in box space
 * ({@code box}, {@code ink}) and, once the analyzer has run, in display space ({@code dbox}, {@code dink}).
 */
final class Glyph {

    final int page;
    final int seq;
    final String text;
    final PDFont font;
    final String fontName;
    final float size;
    final int rgb;
    final boolean bold;
    final boolean italic;
    /** Advance width by font ascent/descent, box space. */
    final Rect box;
    /** Ink bounds of the glyph outline, box space (equals box when unknown). */
    final Rect ink;
    final float originX;
    final float originY;
    final float dirX;
    final float dirY;
    final int code;
    final ContentLocation loc;
    final int renderMode;
    final boolean ocr;

    Rect dbox;
    Rect dink;
    float dOriginX;
    float dOriginY;
    /** True when the glyph reads left to right in display space. */
    boolean horizontal;

    /** Text state when drawn: Tfs, Tc, Tw (if it applied to this code), Tz/100. */
    float tfs;
    float tc;
    float tw;
    float th = 1f;

    Glyph(int page, int seq, String text, PDFont font, String fontName, float size, int rgb, boolean bold,
          boolean italic, Rect box, Rect ink, float originX, float originY, float dirX, float dirY, int code,
          ContentLocation loc, int renderMode, boolean ocr) {
        this.page = page;
        this.seq = seq;
        this.text = text;
        this.font = font;
        this.fontName = fontName;
        this.size = size;
        this.rgb = rgb;
        this.bold = bold;
        this.italic = italic;
        this.box = box;
        this.ink = ink;
        this.originX = originX;
        this.originY = originY;
        this.dirX = dirX;
        this.dirY = dirY;
        this.code = code;
        this.loc = loc;
        this.renderMode = renderMode;
        this.ocr = ocr;
    }

    /** The point the editor tests against a REMOVE box: the ink centre, else the layout centre. */
    float[] centre() {
        Rect r = ink != null ? ink : box;
        return new float[] {r.cx(), r.cy()};
    }

    boolean isSpace() {
        return text.isBlank();
    }

    @Override
    public String toString() {
        return "Glyph[" + text + " p" + page + " " + box + "]";
    }
}
