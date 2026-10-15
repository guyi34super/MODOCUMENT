package com.modocument.doccreation.pdftemplate;

/** Vector and image geometry collected from page content (box space, then display space). */
final class Shapes {

    private Shapes() {
    }

    /** A horizontal or vertical stroke: at {@code pos} (y for horizontal, x for vertical) from..to. */
    record Segment(boolean horizontal, float pos, float from, float to) {
        float length() {
            return to - from;
        }

        Rect rect() {
            return horizontal ? new Rect(from, pos, to, pos) : new Rect(pos, from, pos, to);
        }
    }

    /** A filled, axis-aligned rectangle that is not thin enough to be a line. */
    record FilledRect(Rect rect, int rgb) {
    }

    /** An image drawn on the page. */
    record ImagePlacement(Rect rect, ContentLocation loc) {
    }
}
