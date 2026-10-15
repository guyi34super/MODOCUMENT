package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.geom.AffineTransform;

/**
 * Geometry of one page. Three spaces are used:
 * <ul>
 *   <li><b>raw user space</b>: what the content stream draws in;</li>
 *   <li><b>box space</b> ({@link PdfBox}): raw user space shifted so the cropBox lower-left is the origin
 *       (contract §2);</li>
 *   <li><b>display space</b>: box space turned by the page's /Rotate so text reads upright, origin at the
 *       displayed lower-left corner, y up. Layout analysis runs here.</li>
 * </ul>
 * The rotation formulas match how PDFRenderer draws a rotated page.
 */
final class PageFrame {

    final int index;
    final float width;
    final float height;
    final int rotation;
    final float llx;
    final float lly;

    PageFrame(int index, float width, float height, int rotation, float llx, float lly) {
        this.index = index;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.llx = llx;
        this.lly = lly;
    }

    static PageFrame of(int index, PDPage page) {
        PDRectangle crop = page.getCropBox();
        return new PageFrame(index, crop.getWidth(), crop.getHeight(), normalizeRotation(page.getRotation()),
                crop.getLowerLeftX(), crop.getLowerLeftY());
    }

    static int normalizeRotation(int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        return (Math.round(r / 90f) * 90) % 360;
    }

    PageInfo info() {
        return new PageInfo(index, width, height, rotation);
    }

    float displayWidth() {
        return rotation == 90 || rotation == 270 ? height : width;
    }

    float displayHeight() {
        return rotation == 90 || rotation == 270 ? width : height;
    }

    /** Box-space point to display space. */
    float[] toDisplay(float x, float y) {
        return switch (rotation) {
            case 90 -> new float[] {y, width - x};
            case 180 -> new float[] {width - x, height - y};
            case 270 -> new float[] {height - y, x};
            default -> new float[] {x, y};
        };
    }

    /** Display-space point to box space. */
    float[] toBoxSpace(float dx, float dy) {
        return switch (rotation) {
            case 90 -> new float[] {width - dy, dx};
            case 180 -> new float[] {width - dx, height - dy};
            case 270 -> new float[] {dy, height - dx};
            default -> new float[] {dx, dy};
        };
    }

    Rect toDisplay(Rect r) {
        float[] a = toDisplay(r.x0(), r.y0());
        float[] b = toDisplay(r.x1(), r.y1());
        return new Rect(a[0], a[1], b[0], b[1]);
    }

    Rect toBoxSpace(Rect r) {
        float[] a = toBoxSpace(r.x0(), r.y0());
        float[] b = toBoxSpace(r.x1(), r.y1());
        return new Rect(a[0], a[1], b[0], b[1]);
    }

    /** Maps display coordinates to raw user space (for drawing upright text on a rotated page). */
    AffineTransform displayToRaw() {
        AffineTransform t = AffineTransform.getTranslateInstance(llx, lly);
        switch (rotation) {
            case 90 -> t.concatenate(new AffineTransform(0, 1, -1, 0, width, 0));
            case 180 -> t.concatenate(new AffineTransform(-1, 0, 0, -1, width, height));
            case 270 -> t.concatenate(new AffineTransform(0, -1, 1, 0, 0, height));
            default -> {
            }
        }
        return t;
    }

    /** Box space to the pixel space of a PDFRenderer image at the given scale (pixels per point). */
    AffineTransform boxToPixels(float scale) {
        AffineTransform g = AffineTransform.getScaleInstance(scale, scale);
        if (rotation != 0) {
            float tx = 0;
            float ty = 0;
            switch (rotation) {
                case 90 -> tx = height;
                case 270 -> ty = width;
                case 180 -> {
                    tx = width;
                    ty = height;
                }
                default -> {
                }
            }
            g.translate(tx, ty);
            g.rotate(Math.toRadians(rotation));
        }
        g.translate(0, height);
        g.scale(1, -1);
        return g;
    }
}
