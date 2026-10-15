package com.modocument.doccreation.pdftemplate;

import java.util.ArrayList;
import java.util.List;

/** A proposed region (and, for data, what to remove), in display space, before it becomes a TemplateRegion. */
final class Candidate {

    /** Where the candidate came from. */
    enum Origin { ACRO, LEADER, LINE, CELL, BOX, CHECKBOX, LABEL_SPACE, IMAGE_BOX, DATA, AI }

    String id;
    final int page;
    final Origin origin;
    RegionKind kind;
    Rect box;
    String label;
    TextRun labelRun;
    String group;
    double confidence;
    RegionSource source;
    String acroName;
    String fontName;
    float fontSize;
    int rgb;
    Align align = Align.LEFT;
    int maxLines = 1;
    boolean required;
    String sample;
    /** Data glyphs to cut (empty for blank regions). */
    List<Glyph> valueGlyphs = new ArrayList<>();
    /** Display-space REMOVE box (data only). */
    Rect removeBox;
    /** True when the data is pixels (scan, image), not glyphs. */
    boolean imageData;
    /** Blank candidate this data candidate was merged with (so it is not emitted twice). */
    Candidate mergedBlank;
    String key;
    int sortOrder;

    Candidate(int page, Origin origin, RegionKind kind, Rect box) {
        this.page = page;
        this.origin = origin;
        this.kind = kind;
        this.box = box;
    }

    boolean isData() {
        return removeBox != null || !valueGlyphs.isEmpty();
    }

    @Override
    public String toString() {
        return origin + "[" + label + " " + kind + " p" + page + " " + box + (sample == null ? "" : " = '" + sample + "'")
                + " c=" + confidence + "]";
    }
}
