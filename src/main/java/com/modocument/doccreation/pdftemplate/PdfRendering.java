package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Page images and the pixel-exactness check (contract §1: 150 DPI, 0 differing pixels outside the masks). */
final class PdfRendering {

    static final int MAX_PREVIEW_DPI = 200;
    static final int MAX_VERIFY_DPI = 300;

    private PdfRendering() {
    }

    static byte[] renderPng(byte[] pdf, int page, int dpi) {
        PDDocument doc = PdfDocuments.load(pdf);
        try {
            if (page < 0 || page >= doc.getNumberOfPages()) {
                throw new IllegalArgumentException("No page " + page);
            }
            int d = Math.max(10, Math.min(MAX_PREVIEW_DPI, dpi));
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(page, d, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the page.", e);
        } finally {
            PdfDocuments.closeQuietly(doc);
        }
    }

    /**
     * Renders the first {@code pageLimit} pages of both PDFs (all base pages when negative) and counts
     * differing RGB pixels outside the allowed areas (mapped to pixels, plus one pixel of tolerance). Extra
     * candidate pages are not compared; a missing candidate page counts as fully different.
     */
    static ExactnessReport verify(byte[] base, byte[] candidate, List<PdfBox> allowed, int dpi, int pageLimit) {
        int d = Math.max(10, Math.min(MAX_VERIFY_DPI, dpi));
        PDDocument a = PdfDocuments.load(base);
        PDDocument b = null;
        try {
            b = PdfDocuments.load(candidate);
            PDFRenderer ra = new PDFRenderer(a);
            PDFRenderer rb = new PDFRenderer(b);
            int pages = pageLimit < 0 ? a.getNumberOfPages() : Math.min(pageLimit, a.getNumberOfPages());
            long total = 0;
            List<Integer> diffPages = new ArrayList<>();
            float scale = d / 72f;
            for (int i = 0; i < pages; i++) {
                BufferedImage ia = ra.renderImageWithDPI(i, d, ImageType.RGB);
                long diff;
                if (i >= b.getNumberOfPages()) {
                    diff = (long) ia.getWidth() * ia.getHeight();
                } else {
                    BufferedImage ib = rb.renderImageWithDPI(i, d, ImageType.RGB);
                    boolean[] mask = mask(PageFrame.of(i, a.getPage(i)), allowed, i, scale, ia.getWidth(), ia.getHeight());
                    diff = compare(ia, ib, mask);
                }
                if (diff > 0) {
                    diffPages.add(i);
                    total += diff;
                }
            }
            return new ExactnessReport(total == 0, total, List.copyOf(diffPages), d);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render for the exactness check.", e);
        } finally {
            PdfDocuments.closeQuietly(a);
            PdfDocuments.closeQuietly(b);
        }
    }

    static boolean[] mask(PageFrame frame, List<PdfBox> allowed, int page, float scale, int w, int h) {
        boolean[] mask = new boolean[w * h];
        if (allowed == null) {
            return mask;
        }
        AffineTransform t = frame.boxToPixels(scale);
        for (PdfBox box : allowed) {
            if (box == null || box.page() != page) {
                continue;
            }
            Point2D p = t.transform(new Point2D.Float(box.x(), box.y()), null);
            Point2D q = t.transform(new Point2D.Float(box.x() + box.w(), box.y() + box.h()), null);
            int x0 = Math.max(0, (int) Math.floor(Math.min(p.getX(), q.getX())) - 1);
            int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(p.getX(), q.getX())) + 1);
            int y0 = Math.max(0, (int) Math.floor(Math.min(p.getY(), q.getY())) - 1);
            int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(p.getY(), q.getY())) + 1);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    mask[y * w + x] = true;
                }
            }
        }
        return mask;
    }

    static long compare(BufferedImage a, BufferedImage b, boolean[] mask) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return (long) Math.max(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight());
        }
        int[] pa = pixels(a);
        int[] pb = pixels(b);
        long diff = 0;
        for (int i = 0; i < pa.length; i++) {
            if (((pa[i] ^ pb[i]) & 0xFFFFFF) != 0 && (mask == null || !mask[i])) {
                diff++;
            }
        }
        return diff;
    }

    private static int[] pixels(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB && img.getRaster().getDataBuffer() instanceof DataBufferInt db) {
            return db.getData();
        }
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
