package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.Closeable;
import java.io.IOException;

/**
 * Draws on a page in display space through one appended content stream (the original content is wrapped
 * in q/Q, so nothing drawn here can change how it renders). Opened lazily.
 */
final class PageCanvas implements Closeable {

    private final PDDocument doc;
    private final PDPage page;
    private final PageFrame frame;
    private PDPageContentStream cs;

    PageCanvas(PDDocument doc, PDPage page, PageFrame frame) {
        this.doc = doc;
        this.page = page;
        this.frame = frame;
    }

    PageFrame frame() {
        return frame;
    }

    private PDPageContentStream cs() throws IOException {
        if (cs == null) {
            cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true);
            cs.saveGraphicsState();
            cs.transform(new Matrix(frame.displayToRaw()));
        }
        return cs;
    }

    void fillRect(Rect r, int rgb) throws IOException {
        PDPageContentStream s = cs();
        s.saveGraphicsState();
        s.setNonStrokingColor(new Color(rgb));
        s.addRect(r.x0(), r.y0(), r.w(), r.h());
        s.fill();
        s.restoreGraphicsState();
    }

    /** Starts a clipped group; pair with {@link #endClip()}. */
    void beginClip(Rect clip) throws IOException {
        PDPageContentStream s = cs();
        s.saveGraphicsState();
        s.addRect(clip.x0(), clip.y0(), clip.w(), clip.h());
        s.clip();
    }

    void endClip() throws IOException {
        cs().restoreGraphicsState();
    }

    void text(PDFont font, float size, int rgb, float x, float baseline, String text) throws IOException {
        String safe = encodable(font, text);
        if (safe.isEmpty()) {
            return;
        }
        PDPageContentStream s = cs();
        s.beginText();
        s.setFont(font, size);
        s.setNonStrokingColor(new Color(rgb));
        s.newLineAtOffset(x, baseline);
        s.showText(safe);
        s.endText();
    }

    void image(PDImageXObject img, Rect box) throws IOException {
        float iw = Math.max(1, img.getWidth());
        float ih = Math.max(1, img.getHeight());
        float scale = Math.min(box.w() / iw, box.h() / ih);
        float w = iw * scale;
        float h = ih * scale;
        float x = box.x0() + (box.w() - w) / 2f;
        float y = box.y0() + (box.h() - h) / 2f;
        cs().drawImage(img, x, y, w, h);
    }

    void checkMark(Rect box, int rgb) throws IOException {
        PDPageContentStream s = cs();
        float side = Math.min(box.w(), box.h());
        float x = box.cx() - side / 2f;
        float y = box.cy() - side / 2f;
        s.saveGraphicsState();
        s.setStrokingColor(new Color(rgb));
        s.setLineWidth(Math.max(0.8f, side * 0.12f));
        s.setLineCapStyle(1);
        s.setLineJoinStyle(1);
        s.moveTo(x + side * 0.18f, y + side * 0.52f);
        s.lineTo(x + side * 0.42f, y + side * 0.24f);
        s.lineTo(x + side * 0.84f, y + side * 0.80f);
        s.stroke();
        s.restoreGraphicsState();
    }

    /** Drops characters the font cannot show (they would make PDFBox throw). */
    static String encodable(PDFont font, String text) {
        StringBuilder sb = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String c = new String(Character.toChars(cp));
            try {
                font.encode(c);
                sb.append(c);
            } catch (IOException | RuntimeException e) {
                // not in the font: left out
            }
        });
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        if (cs != null) {
            cs.restoreGraphicsState();
            cs.close();
            cs = null;
        }
    }
}
