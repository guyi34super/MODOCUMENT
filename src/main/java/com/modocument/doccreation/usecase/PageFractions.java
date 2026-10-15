package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfBox;

/**
 * The API layer's coordinate mapping (docs/doccreation/pdf-templates-contract.md
 * §2): boxes are stored as {@link PdfBox} in PDF points in the UNROTATED
 * cropBox user space (origin bottom-left); the frontend draws with top-left
 * page FRACTIONS of the page image as displayed, i.e. already rotated
 * clockwise by the page's /Rotate. The frontend never deals with rotation.
 *
 * <p>Unrotated top-left fractions: {@code ux = x / width},
 * {@code uy = 1 - (y + h) / height}, {@code uw = w / width},
 * {@code uh = h / height}. A clockwise display rotation then maps a point
 * (u, v) to (1 - v, u) for 90°, (1 - u, 1 - v) for 180° and (v, 1 - u) for 270°.
 */
public final class PageFractions {

    /** Rounding slack when a box touches the page edge. */
    private static final double EPSILON = 1e-6;

    private PageFractions() {
    }

    /** A box as top-left fractions of the displayed page image. */
    public record Fractions(double fx, double fy, double fw, double fh) {
    }

    public static Fractions toFractions(PdfBox box, PageInfo page) {
        double width = page.width();
        double height = page.height();
        double ux = box.x() / width;
        double uy = 1 - ((double) box.y() + box.h()) / height;
        double uw = box.w() / width;
        double uh = box.h() / height;
        return switch (normalizedRotation(page.rotation())) {
            case 90 -> new Fractions(1 - (uy + uh), ux, uh, uw);
            case 180 -> new Fractions(1 - (ux + uw), 1 - (uy + uh), uw, uh);
            case 270 -> new Fractions(uy, 1 - (ux + uw), uh, uw);
            default -> new Fractions(ux, uy, uw, uh);
        };
    }

    /**
     * The inverse of {@link #toFractions}: displayed fractions back to a box
     * in points.
     *
     * @throws IllegalArgumentException when the fractions are not a box inside the page
     */
    public static PdfBox toBox(int pageIndex, Fractions f, PageInfo page) {
        requireInside(f);
        double ux;
        double uy;
        double uw;
        double uh;
        switch (normalizedRotation(page.rotation())) {
            case 90 -> {
                ux = f.fy();
                uw = f.fh();
                uh = f.fw();
                uy = 1 - (f.fx() + f.fw());
            }
            case 180 -> {
                ux = 1 - (f.fx() + f.fw());
                uy = 1 - (f.fy() + f.fh());
                uw = f.fw();
                uh = f.fh();
            }
            case 270 -> {
                uy = f.fx();
                uh = f.fw();
                uw = f.fh();
                ux = 1 - (f.fy() + f.fh());
            }
            default -> {
                ux = f.fx();
                uy = f.fy();
                uw = f.fw();
                uh = f.fh();
            }
        }
        float x = (float) (clamp(ux) * page.width());
        float w = (float) (uw * page.width());
        float h = (float) (uh * page.height());
        float y = (float) ((1 - clamp(uy) - uh) * page.height());
        return new PdfBox(pageIndex, x, Math.max(0f, y), w, h);
    }

    private static void requireInside(Fractions f) {
        double[] values = {f.fx(), f.fy(), f.fw(), f.fh()};
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("A box has a coordinate that is not a number.");
            }
        }
        if (f.fw() <= 0 || f.fh() <= 0) {
            throw new IllegalArgumentException("A box needs a positive width and height.");
        }
        if (f.fx() < -EPSILON || f.fy() < -EPSILON
                || f.fx() + f.fw() > 1 + EPSILON || f.fy() + f.fh() > 1 + EPSILON) {
            throw new IllegalArgumentException("A box lies (partly) outside its page.");
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    static int normalizedRotation(int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        return r - r % 90;
    }
}
