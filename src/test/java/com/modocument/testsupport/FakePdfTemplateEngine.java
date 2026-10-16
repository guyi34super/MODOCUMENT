package com.modocument.testsupport;

import com.modocument.doccreation.pdftemplate.Align;
import com.modocument.doccreation.pdftemplate.AnalysisOptions;
import com.modocument.doccreation.pdftemplate.AnalysisResult;
import com.modocument.doccreation.pdftemplate.EditKind;
import com.modocument.doccreation.pdftemplate.EditSource;
import com.modocument.doccreation.pdftemplate.ExactnessReport;
import com.modocument.doccreation.pdftemplate.FillOptions;
import com.modocument.doccreation.pdftemplate.FillResult;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.PdfTemplateEngine;
import com.modocument.doccreation.pdftemplate.PdfTemplateException;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.RegionSource;
import com.modocument.doccreation.pdftemplate.TemplateEdit;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.pdftemplate.UploadFlow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A hand-written stand-in for the engine BE-1 implements (contract §7): real
 * PDFs through PDFBox, but trivial behaviour a test can steer and inspect.
 * <ul>
 *   <li>{@link #analyze}: refuses non-PDFs/encrypted/over-500-page files like
 *       the contract says; proposes {@link #proposedRegions} (default: a
 *       name field and two photo boxes on page 0) and, WITH_DATA, the
 *       {@link #proposedRemovals}; AUTO resolves to {@link #looksFilled}.</li>
 *   <li>{@link #applyEdits}: paints a white box per REMOVE and writes the
 *       text of text edits; records the last call.</li>
 *   <li>{@link #fill}: writes {@code key=value} lines (WinAnsi-safe) on
 *       page 0, so PDFTextStripper reads the values back; a value longer
 *       than {@link #continueOver} characters goes to an added page.
 *       {@link #failExactness} makes the self-check fail.</li>
 * </ul>
 * One bean per cached context: tests call {@link #reset()} first.
 */
public class FakePdfTemplateEngine implements PdfTemplateEngine {

    public volatile boolean looksFilled;
    public volatile boolean failExactness;
    public volatile int continueOver = 400;
    public volatile List<TemplateRegion> proposedRegions;
    public volatile List<TemplateEdit> proposedRemovals;
    public volatile String detectedBy = "CODE";

    public volatile AnalysisOptions lastAnalysisOptions;
    public volatile List<TemplateEdit> lastEdits;
    public volatile Map<String, byte[]> lastEditImages;
    public volatile boolean lastClearAcroForm;
    public volatile Map<String, String> lastFillValues;
    public volatile Map<String, byte[]> lastFillImages;
    public volatile FillOptions lastFillOptions;
    public final AtomicInteger renders = new AtomicInteger();

    public FakePdfTemplateEngine() {
        reset();
    }

    public final void reset() {
        looksFilled = false;
        failExactness = false;
        continueOver = 400;
        proposedRegions = defaultRegions();
        proposedRemovals = defaultRemovals();
        detectedBy = "CODE";
        lastAnalysisOptions = null;
        lastEdits = null;
        lastEditImages = null;
        lastClearAcroForm = false;
        lastFillValues = null;
        lastFillImages = null;
        lastFillOptions = null;
        renders.set(0);
    }

    public static List<TemplateRegion> defaultRegions() {
        return List.of(
                region("full_name", "Full name", "Applicant", RegionKind.TEXT, new PdfBox(0, 100, 700, 200, 20), 0,
                        "John Specimen"),
                region("notes", "Notes", "Applicant", RegionKind.MULTILINE, new PdfBox(0, 100, 500, 300, 60), 1,
                        null),
                region("passport_photo", "Passport photo", "Photos", RegionKind.IMAGE,
                        new PdfBox(0, 400, 600, 120, 150), 2, null),
                region("house_front", "House front", "Photos", RegionKind.IMAGE, new PdfBox(0, 100, 100, 200, 150),
                        3, null));
    }

    public static List<TemplateEdit> defaultRemovals() {
        return List.of(new TemplateEdit("rm-1", EditKind.REMOVE, new PdfBox(0, 100, 700, 200, 20), null, null,
                null, null, null, EditSource.DATA_REMOVAL, 0.8, "John Specimen", "full_name"));
    }

    public static TemplateRegion region(String key, String label, String group, RegionKind kind, PdfBox box,
                                        int sortOrder, String sample) {
        int maxLines = switch (kind) {
            case MULTILINE -> 3;
            case TEXT, DATE, NUMBER, CHOICE -> 1;
            default -> 0;
        };
        return new TemplateRegion(key, label, group, kind, box, "Helvetica", 0f, "#000000", Align.LEFT, maxLines,
                null, false, sortOrder, RegionSource.DETECTED, 0.9, sample);
    }

    // ------------------------------------------------------------- engine

    @Override
    public AnalysisResult analyze(byte[] pdf, UploadFlow flow, AnalysisOptions options) {
        lastAnalysisOptions = options;
        List<PageInfo> pages = pages(pdf);
        if (pages.size() > 500) {
            throw new PdfTemplateException("TOO_MANY_PAGES", "The PDF has more than 500 pages.");
        }
        UploadFlow used = flow == UploadFlow.AUTO ? (looksFilled ? UploadFlow.WITH_DATA : UploadFlow.BLANK) : flow;
        List<TemplateRegion> regions = new ArrayList<>();
        for (TemplateRegion r : proposedRegions) {
            if (r.box().page() < pages.size()) {
                regions.add(used == UploadFlow.WITH_DATA ? r : withoutSample(r));
            }
        }
        List<TemplateEdit> removals = used == UploadFlow.WITH_DATA ? proposedRemovals : List.of();
        return new AnalysisResult(pages.size(), pages, used, false, looksFilled, regions, removals,
                used == UploadFlow.WITH_DATA ? List.of("Check the removed data.") : List.of(), detectedBy);
    }

    private static TemplateRegion withoutSample(TemplateRegion r) {
        return new TemplateRegion(r.key(), r.label(), r.group(), r.kind(), r.box(), r.fontName(), r.fontSize(),
                r.color(), r.align(), r.maxLines(), r.acroFieldName(), r.required(), r.sortOrder(), r.source(),
                r.confidence(), null);
    }

    @Override
    public byte[] applyEdits(byte[] originalPdf, List<TemplateEdit> edits, Map<String, byte[]> images,
                             boolean clearAcroForm) {
        lastEdits = List.copyOf(edits);
        lastEditImages = Map.copyOf(images);
        lastClearAcroForm = clearAcroForm;
        try (PDDocument doc = load(originalPdf)) {
            for (TemplateEdit edit : edits) {
                PDPage page = doc.getPage(edit.box().page());
                try (PDPageContentStream out = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    if (edit.kind() == EditKind.REMOVE) {
                        out.setNonStrokingColor(1f, 1f, 1f);
                        out.addRect(edit.box().x(), edit.box().y(), edit.box().w(), edit.box().h());
                        out.fill();
                    } else if (edit.text() != null) {
                        writeLine(out, edit.box().x(), edit.box().y(), edit.text());
                    }
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public FillResult fill(byte[] templatePdf, List<TemplateRegion> regions, Map<String, String> values,
                           Map<String, byte[]> images, FillOptions options) {
        lastFillValues = Map.copyOf(values);
        lastFillImages = Map.copyOf(images);
        lastFillOptions = options;
        List<String> continued = new ArrayList<>();
        try (PDDocument doc = load(templatePdf)) {
            PDPage first = doc.getPage(0);
            float y = first.getCropBox().getHeight() - 20;
            try (PDPageContentStream out = new PDPageContentStream(doc, first,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                for (TemplateRegion region : regions) {
                    String value = values.get(region.key());
                    if (value == null) {
                        if (images.containsKey(region.key())) {
                            writeLine(out, 20, y, region.key() + "=[photo]");
                            y -= 12;
                        }
                        continue;
                    }
                    if (value.length() > continueOver && options.continuationPages()) {
                        continued.add(region.key());
                        writeLine(out, 20, y, region.key() + "=(continued)");
                    } else {
                        writeLine(out, 20, y, region.key() + "=" + value);
                    }
                    y -= 12;
                }
            }
            for (String key : continued) {
                PDPage extra = new PDPage(new PDRectangle(first.getCropBox().getWidth(),
                        first.getCropBox().getHeight()));
                doc.addPage(extra);
                try (PDPageContentStream out = new PDPageContentStream(doc, extra)) {
                    writeLine(out, 20, extra.getCropBox().getHeight() - 20, key + "=" + values.get(key));
                }
            }
            byte[] pdf = save(doc);
            ExactnessReport report = options.verify()
                    ? new ExactnessReport(!failExactness, failExactness ? 42 : 0,
                            failExactness ? List.of(0) : List.of(), 150)
                    : null;
            return new FillResult(pdf, continued, continued.size(), report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<PageInfo> pages(byte[] pdf) {
        try (PDDocument doc = load(pdf)) {
            List<PageInfo> pages = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                pages.add(new PageInfo(i, page.getCropBox().getWidth(), page.getCropBox().getHeight(),
                        page.getRotation()));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] renderPage(byte[] pdf, int page, int dpi) {
        renders.incrementAndGet();
        try (PDDocument doc = load(pdf)) {
            BufferedImage image = new PDFRenderer(doc).renderImageWithDPI(page, Math.min(dpi, 72));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ExactnessReport verify(byte[] base, byte[] candidate, List<PdfBox> allowedAreas, int dpi) {
        return new ExactnessReport(!failExactness, 0, List.of(), dpi);
    }

    // ------------------------------------------------------------ helpers

    private static PDDocument load(byte[] pdf) {
        try {
            PDDocument doc = Loader.loadPDF(pdf);
            if (doc.isEncrypted()) {
                doc.close();
                throw new PdfTemplateException("ENCRYPTED", "The PDF is password-protected.");
            }
            return doc;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new PdfTemplateException("ENCRYPTED", "The PDF is password-protected.", e);
        } catch (IOException e) {
            throw new PdfTemplateException("NOT_A_PDF", "Not a readable PDF.", e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static void writeLine(PDPageContentStream out, float x, float y, String text) throws IOException {
        StringBuilder safe = new StringBuilder();
        for (char c : text.toCharArray()) {
            safe.append(c >= 32 && c < 127 ? c : '?');
        }
        out.beginText();
        out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
        out.newLineAtOffset(x, y);
        out.showText(safe.toString());
        out.endText();
    }

    // ------------------------------------------------------------- fixtures

    /** A blank A4 PDF of {@code pages} pages; {@code rotation} on every page. */
    public static byte[] blankPdf(int pages, int rotation) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setRotation(rotation);
                doc.addPage(page);
                try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                    writeLine(out, 50, 780, "Application form, page " + (i + 1));
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
