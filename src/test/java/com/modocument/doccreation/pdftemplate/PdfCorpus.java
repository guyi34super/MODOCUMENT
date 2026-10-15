package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic test corpus: each document comes as a BLANK form and the same form FILLED with known data,
 * plus the truth (the fill-in regions of the blank, the data values of the filled one). Built with PDFBox.
 */
final class PdfCorpus {

    /** A data value of the filled twin; box is the area of the value text (box space). */
    record Value(int page, String label, String text, PdfBox box) {
    }

    /** A fill-in region of the blank twin; type is ACRO, LEADER, BOX, CELL, CHECKBOX, LINE, IMAGE. */
    record Region(int page, String label, RegionKind kind, PdfBox box, String type) {
    }

    record Doc(String name, byte[] blank, byte[] filled, List<Region> regions, List<Value> values, boolean needsOcr) {
        @Override
        public String toString() {
            return name;
        }
    }

    private interface Builder {
        void build(PDDocument doc, boolean filled, Truth truth) throws IOException;
    }

    static final class Truth {
        final List<Region> regions = new ArrayList<>();
        final List<Value> values = new ArrayList<>();
        boolean filled;

        void region(int page, String label, RegionKind kind, float x, float y, float w, float h, String type) {
            if (!filled) {
                regions.add(new Region(page, label, kind, new PdfBox(page, x, y, w, h), type));
            }
        }

        void value(int page, String label, String text, float x, float y, float w, float h) {
            if (filled) {
                values.add(new Value(page, label, text, new PdfBox(page, x, y, w, h)));
            }
        }
    }

    private PdfCorpus() {
    }

    static List<Doc> all() {
        return List.of(acroForm(), flatForm(), tableForm(), letterhead(), twoColumn(), mixedPages(), cidGreek(),
                formXObject(), scanned());
    }

    static Doc make(String name, Builder builder) {
        return make(name, builder, false);
    }

    static Doc make(String name, Builder builder, boolean ocr) {
        Truth blankTruth = new Truth();
        Truth filledTruth = new Truth();
        filledTruth.filled = true;
        byte[] blank = render(builder, false, blankTruth);
        byte[] filled = render(builder, true, filledTruth);
        return new Doc(name, blank, filled, blankTruth.regions, filledTruth.values, ocr);
    }

    private static byte[] render(Builder builder, boolean filled, Truth truth) {
        try (PDDocument doc = new PDDocument()) {
            builder.build(doc, filled, truth);
            doc.setDocumentId(42L);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    static PDFont helv() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    static PDFont helvBold() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    static PDFont liberation(PDDocument doc, String name) throws IOException {
        return PDType0Font.load(doc, new ByteArrayInputStream(FontResolver.bundledBytes(name)), true);
    }

    static void text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    static void text(PDPageContentStream cs, PDFont font, float size, Color c, float x, float y, String s)
            throws IOException {
        cs.setNonStrokingColor(c);
        text(cs, font, size, x, y, s);
        cs.setNonStrokingColor(Color.BLACK);
    }

    static float width(PDFont font, float size, String s) throws IOException {
        return font.getStringWidth(s) / 1000f * size;
    }

    /** Draws "label" (blank) or "label value" (filled) as one text operation and records the value. */
    static void pair(PDPageContentStream cs, Truth t, boolean filled, int page, PDFont font, float size, float x,
                     float y, String label, String value) throws IOException {
        if (!filled) {
            text(cs, font, size, x, y, label);
            return;
        }
        String full = label + " " + value;
        text(cs, font, size, x, y, full);
        float vx = x + width(font, size, label + " ");
        t.value(page, label, value, vx, y - 0.22f * size, width(font, size, value), size * 1.15f);
    }

    /** A value drawn on its own (separate text block). */
    static void value(PDPageContentStream cs, Truth t, int page, PDFont font, float size, float x, float y,
                      String label, String value) throws IOException {
        text(cs, font, size, x, y, value);
        t.value(page, label, value, x, y - 0.22f * size, width(font, size, value), size * 1.15f);
    }

    static void rect(PDPageContentStream cs, float x, float y, float w, float h) throws IOException {
        cs.addRect(x, y, w, h);
        cs.stroke();
    }

    static void line(PDPageContentStream cs, float x0, float y0, float x1, float y1) throws IOException {
        cs.moveTo(x0, y0);
        cs.lineTo(x1, y1);
        cs.stroke();
    }

    static BufferedImage photo(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(70, 110, 160), w, h, new Color(200, 170, 120)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(240, 220, 200));
        g.fillOval(w / 4, h / 6, w / 2, h / 2);
        g.dispose();
        return img;
    }

    static BufferedImage logo(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(31, 78, 121));
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(new Color(240, 160, 30));
        g.setStroke(new BasicStroke(size / 10f));
        g.drawLine(size / 4, size * 3 / 4, size * 3 / 4, size / 4);
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ 1. AcroForm

    static Doc acroForm() {
        return make("acroform", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont helv = helv();
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), helv);
            form.setDefaultResources(dr);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, helvBold(), 16, 72, 780, "Membership Application");
                text(cs, helv, 10, 72, 724, "Full name:");
                text(cs, helv, 10, 72, 694, "Email:");
                text(cs, helv, 10, 72, 664, "Date of birth:");
                text(cs, helv, 10, 72, 634, "Address:");
                text(cs, helv, 10, 90, 563, "I agree to the terms");
            }
            String[][] fields = {
                    {"fullName", "Full name", "720", "16", "Jane Doe"},
                    {"email", "Email", "690", "16", "jane.doe@example.com"},
                    {"dob", "Date of birth", "660", "16", "1990-04-12"},
                    {"address", "Address", "590", "40", "12 High Street\nSpringfield"}};
            for (String[] f : fields) {
                PDTextField tf = new PDTextField(form);
                tf.setPartialName(f[0]);
                tf.setDefaultAppearance("/Helv 10 Tf 0 g");
                if ("address".equals(f[0])) {
                    tf.setMultiline(true);
                }
                PDAnnotationWidget w = tf.getWidgets().get(0);
                float y = Float.parseFloat(f[2]) - 2;
                float h = Float.parseFloat(f[3]);
                w.setRectangle(new PDRectangle(160, y, 300, h));
                w.setPage(page);
                w.setPrinted(true);
                page.getAnnotations().add(w);
                form.getFields().add(tf);
                RegionKind kind = "address".equals(f[0]) ? RegionKind.MULTILINE
                        : "dob".equals(f[0]) ? RegionKind.DATE : RegionKind.TEXT;
                t.region(0, f[1], kind, 160, y, 300, h, "ACRO");
                if (filled) {
                    tf.setValue(f[4]);
                    t.value(0, f[1], f[4], 160, y, 300, h);
                } else {
                    tf.setValue("");
                }
            }
            PDCheckBox cb = new PDCheckBox(form);
            cb.setPartialName("agree");
            PDAnnotationWidget w = cb.getWidgets().get(0);
            w.setRectangle(new PDRectangle(72, 560, 12, 12));
            w.setPage(page);
            page.getAnnotations().add(w);
            form.getFields().add(cb);
            // a minimal on/off appearance
            org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary ap =
                    new org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary();
            org.apache.pdfbox.cos.COSDictionary normal = new org.apache.pdfbox.cos.COSDictionary();
            normal.setItem(COSName.getPDFName("Yes"), appearance(doc, true));
            normal.setItem(COSName.Off, appearance(doc, false));
            ap.getCOSObject().setItem(COSName.N, normal);
            w.setAppearance(ap);
            t.region(0, "I agree to the terms", RegionKind.CHECKBOX, 72, 560, 12, 12, "ACRO");
            if (filled) {
                cb.check();
                t.value(0, "I agree to the terms", "true", 72, 560, 12, 12);
            } else {
                cb.unCheck();
            }
        });
    }

    private static org.apache.pdfbox.cos.COSStream appearance(PDDocument doc, boolean on) throws IOException {
        PDAppearanceStream f = new PDAppearanceStream(doc.getDocument().createCOSStream());
        f.setBBox(new PDRectangle(12, 12));
        try (PDPageContentStream cs = new PDPageContentStream(doc, f)) {
            cs.addRect(0.5f, 0.5f, 11, 11);
            cs.stroke();
            if (on) {
                cs.moveTo(2, 6);
                cs.lineTo(5, 3);
                cs.lineTo(10, 10);
                cs.stroke();
            }
        }
        return f.getCOSObject();
    }

    // ------------------------------------------------------------------ 2. flat form

    static Doc flatForm() {
        return make("flat_form", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont helv = helv();
            PDFont bold = helvBold();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, bold, 16, 72, 780, "Employee Details Form");
                text(cs, bold, 12, 72, 740, "Personal information");
                String nameLine = "Full name: ______________________________";
                text(cs, helv, 10, 72, 710, nameLine);
                float lx = 72 + width(helv, 10, "Full name: ");
                t.region(0, "Full name", RegionKind.TEXT, lx, 708, width(helv, 10, "______________________________"), 11,
                        "LEADER");
                String dobLine = "Date of birth: ____________";
                text(cs, helv, 10, 72, 685, dobLine);
                float dx = 72 + width(helv, 10, "Date of birth: ");
                t.region(0, "Date of birth", RegionKind.DATE, dx, 683, width(helv, 10, "____________"), 11, "LEADER");
                text(cs, helv, 10, 72, 660, "Employee ID:");
                rect(cs, 150, 655, 150, 18);
                t.region(0, "Employee ID", RegionKind.TEXT, 150, 655, 150, 18, "BOX");
                text(cs, helv, 10, 320, 660, "Department:");
                rect(cs, 390, 655, 150, 18);
                t.region(0, "Department", RegionKind.TEXT, 390, 655, 150, 18, "BOX");
                rect(cs, 72, 628, 10, 10);
                text(cs, helv, 10, 88, 629, "Full-time");
                rect(cs, 160, 628, 10, 10);
                text(cs, helv, 10, 176, 629, "Part-time");
                t.region(0, "Full-time", RegionKind.CHECKBOX, 72, 628, 10, 10, "CHECKBOX");
                t.region(0, "Part-time", RegionKind.CHECKBOX, 160, 628, 10, 10, "CHECKBOX");
                text(cs, helv, 10, 460, 796, "Photo:");
                rect(cs, 460, 690, 90, 100);
                t.region(0, "Photo", RegionKind.IMAGE, 460, 690, 90, 100, "IMAGE");
                rect(cs, 380, 380, 150, 70);
                text(cs, helv, 8, 400, 412, "Affix company stamp here");
                t.region(0, "Stamp", RegionKind.IMAGE, 380, 380, 150, 70, "IMAGE");
                text(cs, bold, 12, 72, 590, "Declaration");
                text(cs, helv, 10, 72, 570, "I confirm that the information given in this form is true and complete.");
                line(cs, 72, 520, 250, 520);
                text(cs, helv, 9, 72, 508, "Signature");
                line(cs, 320, 520, 450, 520);
                text(cs, helv, 9, 320, 508, "Date");
                t.region(0, "Signature", RegionKind.SIGNATURE, 72, 520, 178, 14, "LINE");
                t.region(0, "Date", RegionKind.DATE, 320, 520, 130, 14, "LINE");
                if (filled) {
                    value(cs, t, 0, helv, 10, lx + 4, 712, "Full name", "John Smith");
                    value(cs, t, 0, helv, 10, dx + 4, 687, "Date of birth", "14/02/1985");
                    value(cs, t, 0, helv, 10, 154, 660, "Employee ID", "EMP-20931");
                    value(cs, t, 0, helv, 10, 394, 660, "Department", "Finance");
                    text(cs, helv, 9, 74.3f, 630, "X");
                    t.value(0, "Full-time", "true", 72, 628, 10, 10);
                    PDImageXObject img = LosslessFactory.createFromImage(doc, photo(84, 94));
                    cs.drawImage(img, 463, 693, 84, 94);
                    t.value(0, "Photo", "[image]", 463, 693, 84, 94);
                    value(cs, t, 0, helv, 10, 325, 523, "Date", "01/03/2024");
                }
            }
        });
    }

    // ------------------------------------------------------------------ 3. table form

    static Doc tableForm() {
        return make("table_form", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont helv = helv();
            PDFont bold = helvBold();
            float[] xs = {72, 282, 342, 432, 522};
            float top = 700;
            float[] ys = {top, top - 20, top - 38, top - 56, top - 74, top - 92};
            String[] head = {"Description", "Qty", "Unit price", "Amount"};
            String[][] rows = {
                    {"Printer paper A4", "10", "$4.50", "$45.00"},
                    {"Toner cartridge", "2", "$60.00", "$120.00"},
                    {"Stapler", "1", "$12.50", "$12.50"}};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, bold, 16, 72, 780, "Purchase Order");
                text(cs, bold, 12, 72, 720, "Order details");
                cs.setLineWidth(0.8f);
                for (float y : ys) {
                    line(cs, xs[0], y, xs[4], y);
                }
                for (int i = 0; i < xs.length; i++) {
                    // the total row merges the first three columns
                    boolean inner = i == 1 || i == 2;
                    line(cs, xs[i], ys[0], xs[i], inner ? ys[4] : ys[5]);
                }
                for (int c = 0; c < 4; c++) {
                    text(cs, bold, 10, xs[c] + 4, ys[0] - 14, head[c]);
                }
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 4; c++) {
                        float x = xs[c];
                        float y = ys[r + 1];
                        float w = xs[c + 1] - xs[c];
                        t.region(0, head[c] + " " + (r + 1), c == 1 || c >= 2 ? RegionKind.NUMBER : RegionKind.TEXT,
                                x, y - 18, w, 18, "CELL");
                        if (filled) {
                            String v = rows[r][c];
                            float vx = c == 0 ? x + 4 : xs[c + 1] - 4 - width(helv, 10, v);
                            value(cs, t, 0, helv, 10, vx, y - 13, head[c] + " " + (r + 1), v);
                        }
                    }
                }
                text(cs, bold, 10, xs[3] - 4 - width(bold, 10, "Total"), ys[4] - 13, "Total");
                t.region(0, "Total amount", RegionKind.NUMBER, xs[3], ys[5], xs[4] - xs[3], 18, "CELL");
                if (filled) {
                    String v = "$177.50";
                    value(cs, t, 0, helv, 10, xs[4] - 4 - width(helv, 10, v), ys[4] - 13, "Amount", v);
                }
                text(cs, helv, 10, 72, 560, "Approved by: ____________________");
                float ax = 72 + width(helv, 10, "Approved by: ");
                t.region(0, "Approved by", RegionKind.TEXT, ax, 558, width(helv, 10, "____________________"), 11,
                        "LEADER");
                if (filled) {
                    value(cs, t, 0, helv, 10, ax + 4, 562, "Approved by", "M. Patel");
                }
            }
        });
    }

    // ------------------------------------------------------------------ 4. letterhead

    static Doc letterhead() {
        return make("letterhead", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont serif = liberation(doc, "LiberationSerif");
            PDFont serifBold = liberation(doc, "LiberationSerif-Bold");
            Color brand = new Color(31, 78, 121);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDImageXObject logo = LosslessFactory.createFromImage(doc, logo(60));
                cs.drawImage(logo, 72, 760, 50, 50);
                // vector logo: a bar and a circle drawn with curves
                cs.setNonStrokingColor(new Color(240, 160, 30));
                cs.addRect(130, 765, 8, 40);
                cs.fill();
                cs.setNonStrokingColor(brand);
                float cx = 160;
                float cy = 785;
                float r = 14;
                float k = 0.5523f * r;
                cs.moveTo(cx + r, cy);
                cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
                cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
                cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
                cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
                cs.fill();
                cs.setNonStrokingColor(Color.BLACK);
                text(cs, serifBold, 18, brand, 190, 785, "ACME Consulting Ltd");
                text(cs, serif, 9, Color.DARK_GRAY, 190, 770, "12 Market Square, Leeds LS1 2AB");
                text(cs, serif, 9, Color.DARK_GRAY, 190, 758, "Tel: +44 113 496 0000  |  info@acme.example");
                cs.setNonStrokingColor(brand);
                cs.addRect(72, 744, 451, 1.2f);
                cs.fill();
                cs.setNonStrokingColor(Color.BLACK);
                pair(cs, t, filled, 0, serif, 11, 72, 710, "Ref:", "ACM-2024-0117");
                pair(cs, t, filled, 0, serif, 11, 72, 695, "Date:", "12 March 2024");
                pair(cs, t, filled, 0, serif, 11, 72, 670, "To:", "Robert Brown");
                text(cs, serif, 11, 72, 640, "Thank you for choosing ACME Consulting. This letter confirms the scope of the");
                text(cs, serif, 11, 72, 626, "engagement described in our proposal and the fees agreed between us. Please");
                text(cs, serif, 11, 72, 612, "review the details below and contact us with any questions.");
                pair(cs, t, filled, 0, serif, 11, 72, 580, "Amount due:", "£1,250.00");
                text(cs, serif, 11, 72, 540, "Yours sincerely,");
                text(cs, serif, 7, Color.GRAY, 72, 40, "ACME Consulting Ltd is registered in England No. 01234567");
            }
        });
    }

    // ------------------------------------------------------------------ 5. two-column report

    static Doc twoColumn() {
        return make("two_column", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont helv = helv();
            PDFont bold = helvBold();
            String[] left = {
                    "The project continued according to plan during",
                    "the quarter. The design phase was completed and",
                    "the team started the build of the first release.",
                    "Risks are reviewed every two weeks with the",
                    "steering group and recorded in the register."};
            String[] right = {
                    "Spending remains within the approved budget.",
                    "The main costs this quarter were contractor",
                    "fees and the purchase of test equipment.",
                    "No change request was raised."};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, bold, 18, 72, 790, "Quarterly Project Report");
                pair(cs, t, filled, 0, helv, 10, 72, 760, "Prepared by:", "Jane Doe");
                pair(cs, t, filled, 0, helv, 10, 330, 760, "Report date:", "2024-05-01");
                text(cs, bold, 12, 72, 720, "Project Apollo");
                pair(cs, t, filled, 0, helv, 10, 72, 700, "Status:", "On track");
                for (int i = 0; i < left.length; i++) {
                    text(cs, helv, 10, 72, 676 - i * 13, left[i]);
                }
                text(cs, bold, 12, 310, 720, "Budget");
                pair(cs, t, filled, 0, helv, 10, 310, 700, "Spent to date:", "$84,200");
                pair(cs, t, filled, 0, helv, 10, 310, 686, "Forecast:", "$120,000");
                for (int i = 0; i < right.length; i++) {
                    text(cs, helv, 10, 310, 662 - i * 13, right[i]);
                }
                text(cs, helv, 8, 270, 40, "Page 1 of 1");
            }
        });
    }

    // ------------------------------------------------------------------ 6. mixed page sizes, crop box, rotation

    static Doc mixedPages() {
        return make("mixed_pages", (doc, filled, t) -> {
            PDFont helv = helv();
            PDFont bold = helvBold();
            PDPage p1 = new PDPage(new PDRectangle(842, 595));
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                text(cs, bold, 16, 72, 540, "Site Inspection");
                pair(cs, t, filled, 0, helv, 10, 72, 500, "Inspector:", "Maria Garcia");
                pair(cs, t, filled, 0, helv, 10, 400, 500, "Inspection date:", "2024-06-18");
            }
            PDPage p2 = new PDPage(PDRectangle.LETTER);
            p2.setCropBox(new PDRectangle(18, 18, 576, 756));
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                text(cs, bold, 14, 90, 720, "Findings");
                // values are recorded in box space (crop box origin)
                if (!filled) {
                    text(cs, helv, 10, 90, 690, "Site code:");
                } else {
                    text(cs, helv, 10, 90, 690, "Site code: NW-4471");
                    float vx = 90 + width(helv, 10, "Site code: ");
                    t.value(1, "Site code", "NW-4471", vx - 18, 690 - 2.2f - 18, width(helv, 10, "NW-4471"), 11.5f);
                }
                text(cs, helv, 10, 90, 660, "Score:");
                rect(cs, 150, 655, 80, 18);
                t.region(1, "Score", RegionKind.NUMBER, 150 - 18, 655 - 18, 80, 18, "BOX");
                if (filled) {
                    text(cs, helv, 10, 155, 660, "87");
                    t.value(1, "Score", "87", 155 - 18, 660 - 2.2f - 18, width(helv, 10, "87"), 11.5f);
                }
            }
            // A4 portrait page shown landscape: /Rotate 90 with the content drawn turned, so it reads upright
            PDPage p3 = new PDPage(PDRectangle.A4);
            p3.setRotation(90);
            doc.addPage(p3);
            float w = PDRectangle.A4.getWidth();
            try (PDPageContentStream cs = new PDPageContentStream(doc, p3)) {
                cs.transform(new Matrix(0, 1, -1, 0, w, 0));
                text(cs, bold, 14, 72, 520, "Sign-off");
                if (!filled) {
                    text(cs, helv, 10, 72, 490, "Approved by:");
                    text(cs, helv, 10, 72, 470, "Approval date:");
                } else {
                    text(cs, helv, 10, 72, 490, "Approved by: Thomas Lee");
                    text(cs, helv, 10, 72, 470, "Approval date: 2024-06-20");
                    // display (X, Y) -> box space (W - Y, X)
                    float vx = 72 + width(helv, 10, "Approved by: ");
                    float vw = width(helv, 10, "Thomas Lee");
                    t.value(2, "Approved by", "Thomas Lee", w - (490 + 9.3f), vx, 11.5f, vw);
                    float dx = 72 + width(helv, 10, "Approval date: ");
                    float dw = width(helv, 10, "2024-06-20");
                    t.value(2, "Approval date", "2024-06-20", w - (470 + 9.3f), dx, 11.5f, dw);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 7. CID font, Greek and accents

    static Doc cidGreek() {
        return make("cid_greek", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont sans = liberation(doc, "LiberationSans");
            PDFont sansBold = liberation(doc, "LiberationSans-Bold");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, sansBold, 16, 72, 780, "Αίτηση εγγραφής");
                pair(cs, t, filled, 0, sans, 11, 72, 740, "Ονοματεπώνυμο:", "Γιώργος Παπαδόπουλος");
                pair(cs, t, filled, 0, sans, 11, 72, 720, "Πόλη:", "Θεσσαλονίκη");
                pair(cs, t, filled, 0, sans, 11, 72, 700, "Ημερομηνία:", "05/09/2024");
                pair(cs, t, filled, 0, sans, 11, 72, 680, "Adresse:", "Rue de l'Église 4, Genève");
                text(cs, sans, 11, 72, 640, "Η αίτηση υποβάλλεται ηλεκτρονικά και ελέγχεται από την υπηρεσία.");
            }
        });
    }

    // ------------------------------------------------------------------ 8. a Form XObject on two pages

    static Doc formXObject() {
        return make("form_xobject", (doc, filled, t) -> {
            PDFont helv = helv();
            PDFont bold = helvBold();
            PDAppearanceStream header = new PDAppearanceStream(doc.getDocument().createCOSStream());
            header.setBBox(new PDRectangle(0, 0, 595, 80));
            header.setResources(new PDResources());
            try (PDPageContentStream hs = new PDPageContentStream(doc, header)) {
                text(hs, bold, 14, 72, 40, "Northwind Traders");
                text(hs, helv, 8, 72, 28, "Wholesale food and beverages");
                hs.setLineWidth(1f);
                line(hs, 72, 20, 523, 20);
            }
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.saveGraphicsState();
                    cs.transform(Matrix.getTranslateInstance(0, 740));
                    cs.drawForm(header);
                    cs.restoreGraphicsState();
                    if (p == 0) {
                        text(cs, bold, 12, 72, 700, "Invoice");
                        pair(cs, t, filled, 0, helv, 10, 72, 670, "Invoice no:", "INV-5531");
                        pair(cs, t, filled, 0, helv, 10, 72, 650, "Customer:", "Olivia Martin");
                    } else {
                        text(cs, bold, 12, 72, 700, "Delivery");
                        text(cs, helv, 10, 72, 670, "Delivery address:");
                        if (filled) {
                            text(cs, helv, 10, 72, 655, "45 Harbour Road");
                            text(cs, helv, 10, 72, 641, "Dublin D02 X285");
                            t.value(1, "Delivery address", "45 Harbour Road\nDublin D02 X285", 72, 638.8f,
                                    width(helv, 10, "45 Harbour Road"), 25.5f);
                        }
                        pair(cs, t, filled, 1, helv, 10, 72, 600, "Notes:", "Leave at reception");
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ 9. scanned (image-only) form

    static Doc scanned() {
        Doc source = make("scanned_source", (doc, filled, t) -> {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont helv = helv();
            PDFont bold = helvBold();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, bold, 18, 72, 760, "Claim Form");
                pair(cs, t, filled, 0, helv, 14, 72, 700, "Name:", "Peter Novak");
                pair(cs, t, filled, 0, helv, 14, 72, 660, "Policy No:", "PN-778812");
                pair(cs, t, filled, 0, helv, 14, 72, 620, "Date:", "2023-11-30");
            }
        });
        byte[] blank = imageOnly(source.blank());
        byte[] filled = imageOnly(source.filled());
        return new Doc("scanned", blank, filled, List.of(), source.values(), true);
    }

    /** Renders each page at 200 DPI and wraps it as a full-page image (a "scan"). */
    static byte[] imageOnly(byte[] pdf) {
        try (PDDocument src = org.apache.pdfbox.Loader.loadPDF(pdf); PDDocument out = new PDDocument()) {
            PDFRenderer r = new PDFRenderer(src);
            for (int i = 0; i < src.getNumberOfPages(); i++) {
                BufferedImage img = r.renderImageWithDPI(i, 200, ImageType.RGB);
                PDRectangle size = src.getPage(i).getMediaBox();
                PDPage page = new PDPage(size);
                out.addPage(page);
                PDImageXObject x = LosslessFactory.createFromImage(out, img);
                try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                    cs.drawImage(x, 0, 0, size.getWidth(), size.getHeight());
                }
            }
            out.setDocumentId(42L);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
