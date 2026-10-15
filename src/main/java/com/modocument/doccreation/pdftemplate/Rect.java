package com.modocument.doccreation.pdftemplate;

/** Axis-aligned rectangle (x0 &lt; x1, y0 &lt; y1), y up. Used in user and display space alike. */
record Rect(float x0, float y0, float x1, float y1) {

    Rect {
        if (x1 < x0) {
            float t = x0;
            x0 = x1;
            x1 = t;
        }
        if (y1 < y0) {
            float t = y0;
            y0 = y1;
            y1 = t;
        }
    }

    static Rect ofBox(PdfBox box) {
        return new Rect(box.x(), box.y(), box.x() + box.w(), box.y() + box.h());
    }

    static Rect xywh(float x, float y, float w, float h) {
        return new Rect(x, y, x + w, y + h);
    }

    float w() {
        return x1 - x0;
    }

    float h() {
        return y1 - y0;
    }

    float cx() {
        return (x0 + x1) / 2f;
    }

    float cy() {
        return (y0 + y1) / 2f;
    }

    float area() {
        return Math.max(0, w()) * Math.max(0, h());
    }

    Rect union(Rect o) {
        if (o == null) {
            return this;
        }
        return new Rect(Math.min(x0, o.x0), Math.min(y0, o.y0), Math.max(x1, o.x1), Math.max(y1, o.y1));
    }

    boolean intersects(Rect o) {
        return o != null && x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
    }

    Rect intersect(Rect o) {
        float a = Math.max(x0, o.x0);
        float b = Math.max(y0, o.y0);
        float c = Math.min(x1, o.x1);
        float d = Math.min(y1, o.y1);
        if (c <= a || d <= b) {
            return null;
        }
        return new Rect(a, b, c, d);
    }

    boolean contains(float x, float y) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    boolean containsRect(Rect o, float tol) {
        return o.x0 >= x0 - tol && o.x1 <= x1 + tol && o.y0 >= y0 - tol && o.y1 <= y1 + tol;
    }

    Rect expand(float d) {
        return new Rect(x0 - d, y0 - d, x1 + d, y1 + d);
    }

    Rect expand(float dx, float dy) {
        return new Rect(x0 - dx, y0 - dy, x1 + dx, y1 + dy);
    }

    double overlapArea(Rect o) {
        Rect i = intersect(o);
        return i == null ? 0 : i.area();
    }

    double iou(Rect o) {
        double inter = overlapArea(o);
        double uni = area() + o.area() - inter;
        return uni <= 0 ? 0 : inter / uni;
    }

    /** Horizontal overlap length. */
    float xOverlap(Rect o) {
        return Math.max(0, Math.min(x1, o.x1) - Math.max(x0, o.x0));
    }

    /** Vertical overlap length. */
    float yOverlap(Rect o) {
        return Math.max(0, Math.min(y1, o.y1) - Math.max(y0, o.y0));
    }

    PdfBox toBox(int page) {
        return new PdfBox(page, x0, y0, w(), h());
    }
}
