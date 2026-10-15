package com.modocument.doccreation.pdf;

import com.modocument.infrastructure.file.OcrService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds a PDF blueprint as a plain, editable .docx: real body paragraphs in
 * reading order, with heading paragraphs marked by an outline level (and a
 * {@code HeadingN} style id). Section extraction — AI and heuristic alike —
 * only reads body paragraphs, which a layout-faithful conversion (LibreOffice
 * puts every PDF text run in a positioned text box) never produces.
 *
 * <p>Nothing here is tuned to one document. Each page is read on its own
 * (text, scanned → OCR, garbled fonts → OCR), two-column layouts are read
 * column by column, form rows keep label and value apart with tabs, AcroForm
 * values are merged in by position, and heading hints come from, strongest
 * first: bookmarks, tagged-PDF heading tags, larger font sizes, then textual
 * cues (numbering, Section/Part/... prefixes, ALL CAPS, bold, "Label:" lines
 * above fields). The hints only guide; the AI makes the final section call.
 */
@Component
public class PdfBlueprintReflower {

    private static final Logger log = LoggerFactory.getLogger(PdfBlueprintReflower.class);

    static final int MAX_PAGES = 500;
    static final int MAX_OCR_PAGES = 60;
    static final float OCR_DPI = 300f;
    /** Fewer visible characters than this on a page means "scanned". */
    static final int MIN_TEXT_CHARS = 20;
    static final double GARBLED_RATIO = 0.30;
    /** A page image covering this share of the page means "the form itself is a picture". */
    static final double IMAGE_PAGE_SHARE = 0.40;
    /** This many filled paths on a page usually means text drawn as outlines. */
    static final int OUTLINED_TEXT_FILLS = 400;
    /** OCR must find this many times the text layer's characters to replace it. */
    static final double OVERLAY_OCR_GAIN = 1.5;
    static final int MAX_HEADING_LEVEL = 6;
    private static final int MAX_HEADING_CHARS = 120;
    private static final int MAX_CUE_CHARS = 80;
    private static final double HEADING_SIZE_RATIO = 1.15;
    /** More headings than this share of paragraphs means the weak cues are noise. */
    private static final double MAX_HEADING_SHARE = 0.40;
    private static final int[] HEADING_HALF_POINTS = {32, 28, 26, 24, 22, 22};

    private static final Pattern DECIMAL_NUMBERING =
            Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){0,5})[.)]?\\s+\\S");
    private static final Pattern ROMAN_NUMBERING = Pattern.compile("^(?i)([ivxlc]{1,6})[.)]\\s+\\S");
    private static final Pattern LETTER_NUMBERING = Pattern.compile("^([A-Z])[.)]\\s+\\S");
    private static final Pattern PAREN_NUMBERING = Pattern.compile("^\\(([a-z]|[ivx]{1,4}|\\d{1,2})\\)\\s+\\S");
    /** "Section 3", "PART II", "Schedule A", "Annex 1:" — the keyword followed by an identifier. */
    private static final Pattern KEYWORD_PREFIX = Pattern.compile(
            "^(?i)(section|part|chapter|schedule|annex|annexure|appendix|article|clause)\\s+"
                    + "(\\d{1,3}(\\.\\d{1,3})*|[ivxlc]{1,6}|[a-z])\\b");
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?i)(page\\s*)?\\d{1,4}(\\s*(of|/)\\s*\\d{1,4})?$");
    private static final Pattern HEADING_TAG = Pattern.compile("^(?i)h([1-6])?$");

    private final OcrService ocr;

    @Autowired
    public PdfBlueprintReflower(OcrService ocr) {
        this.ocr = ocr;
    }

    /** What came out of a PDF: the .docx bytes plus counts for logging and the fallback decision. */
    public record Reflowed(byte[] docx, int paragraphs, int headings, int pages, int ocrPages) {
        public boolean isEmpty() {
            return paragraphs == 0;
        }
    }

    /**
     * @throws IllegalArgumentException for a password-protected or oversized PDF
     * @throws UncheckedIOException when PDFBox cannot read the file (the caller may try another converter)
     */
    public Reflowed toDocx(Path pdfFile) {
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            document.setAllSecurityToBeRemoved(true);
            int pageCount = document.getNumberOfPages();
            if (pageCount > MAX_PAGES) {
                throw new IllegalArgumentException("This PDF has " + pageCount + " pages; blueprints are limited to "
                        + MAX_PAGES + " pages.");
            }
            return reflow(document);
        } catch (InvalidPasswordException e) {
            throw new IllegalArgumentException(
                    "This PDF is password-protected; upload an unlocked copy or a .docx instead.", e);
        } catch (IOException e) {
            throw new UncheckedIOException("The uploaded file is not a readable PDF.", e);
        }
    }

    // ------------------------------------------------------------------ model

    /** One horizontal run of text on a page (a line piece between wide gaps). */
    record Segment(String text, float x0, float x1, float y, float height, float fontSize, boolean bold,
                   boolean field) {
    }

    /** A reading-order line: one segment, or a form/table row of segments joined by tabs. */
    static final class Line {
        final int page;
        final List<String> cells;
        final float y;
        final float height;
        final float fontSize;
        final boolean bold;
        final boolean ocr;
        int level;
        boolean strongHeading;

        Line(int page, List<String> cells, float y, float height, float fontSize, boolean bold, boolean ocr) {
            this.page = page;
            this.cells = cells;
            this.y = y;
            this.height = height;
            this.fontSize = fontSize;
            this.bold = bold;
            this.ocr = ocr;
        }

        String text() {
            return String.join(" ", cells).strip();
        }

        boolean isRow() {
            return cells.size() > 1;
        }
    }

    record Paragraph(List<String> cells, int level) {
        String text() {
            return String.join(" ", cells).strip();
        }
    }

    record Bookmark(String normalizedTitle, int depth, int page) {
    }

    // ---------------------------------------------------------------- reflow

    private Reflowed reflow(PDDocument document) throws IOException {
        List<Bookmark> bookmarks = bookmarks(document);
        Map<Integer, List<FieldValue>> fieldsByPage = formFields(document);
        List<Line> lines = new ArrayList<>();
        int ocrPages = 0;
        int pageCount = document.getNumberOfPages();
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            PDPage page = document.getPage(pageIndex);
            List<Segment> segments = extractSegments(document, pageIndex);
            segments = mergeFields(segments, fieldsByPage.getOrDefault(pageIndex, List.of()));
            String pageText = joinText(segments);
            if (ocrPages < MAX_OCR_PAGES && visibleChars(pageText) >= MIN_TEXT_CHARS && !garbled(pageText)
                    && looksPrintedAsGraphics(page)) {
                // A scanned or outlined form with typed-in values on top: the
                // text layer holds only the values. OCR reads the printed form
                // too; use it when it clearly found more.
                List<Line> ocrLines = ocrPage(document, pageIndex);
                int ocrChars = ocrLines.stream().mapToInt(l -> visibleChars(l.text())).sum();
                if (ocrChars >= visibleChars(pageText) * OVERLAY_OCR_GAIN + MIN_TEXT_CHARS) {
                    ocrPages++;
                    lines.addAll(ocrLines);
                    continue;
                }
            }
            if (visibleChars(pageText) < MIN_TEXT_CHARS || garbled(pageText)) {
                if (ocrPages < MAX_OCR_PAGES) {
                    List<Line> ocrLines = ocrPage(document, pageIndex);
                    if (!ocrLines.isEmpty()) {
                        ocrPages++;
                        lines.addAll(ocrLines);
                        continue;
                    }
                } else if (ocrPages == MAX_OCR_PAGES) {
                    log.info("PDF blueprint: OCR limit of {} pages reached; later scanned pages are skipped",
                            MAX_OCR_PAGES);
                }
                if (garbled(pageText)) {
                    continue;
                }
            }
            Map<String, Integer> tagLevels = taggedHeadings(page);
            List<Line> pageLines = readingOrder(pageIndex, segments, pageWidth(page));
            for (Line line : pageLines) {
                Integer tagged = tagLevels.get(normalize(line.text()));
                if (tagged != null) {
                    line.level = tagged;
                    line.strongHeading = true;
                }
            }
            lines.addAll(pageLines);
        }

        lines = dropRunningHeadersAndFooters(lines, document);
        applyBookmarks(lines, bookmarks);
        classifyHeadings(lines);
        List<Paragraph> paragraphs = buildParagraphs(lines);
        paragraphs = limitWeakHeadings(paragraphs, lines);
        int headings = (int) paragraphs.stream().filter(p -> p.level() > 0).count();
        log.info("PDF blueprint reflowed: {} pages ({} OCR), {} paragraphs, {} headings",
                pageCount, ocrPages, paragraphs.size(), headings);
        return new Reflowed(writeDocx(paragraphs), paragraphs.size(), headings, pageCount, ocrPages);
    }

    // ------------------------------------------------------------ extraction

    /** Words and their positions, grouped by PDFBox into lines, split here at wide gaps. */
    private List<Segment> extractSegments(PDDocument document, int pageIndex) throws IOException {
        SegmentCollector collector = new SegmentCollector();
        collector.setSortByPosition(true);
        collector.setSuppressDuplicateOverlappingText(true);
        collector.setStartPage(pageIndex + 1);
        collector.setEndPage(pageIndex + 1);
        collector.getText(document);
        collector.flushLine();
        return collector.segments;
    }

    /** Receives PDFBox's per-word callbacks and turns each line into gap-separated segments. */
    static final class SegmentCollector extends PDFTextStripper {
        final List<Segment> segments = new ArrayList<>();
        private final List<List<TextPosition>> words = new ArrayList<>();

        SegmentCollector() throws IOException {
            super();
            setLineSeparator("\n");
            setWordSeparator(" ");
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (!textPositions.isEmpty()) {
                words.add(new ArrayList<>(textPositions));
            }
        }

        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        void flushLine() {
            if (words.isEmpty()) {
                return;
            }
            List<List<TextPosition>> piece = new ArrayList<>();
            float previousEnd = Float.NaN;
            for (List<TextPosition> word : words) {
                float start = word.get(0).getXDirAdj();
                float size = Math.max(1f, fontSize(word.get(0)));
                if (!piece.isEmpty() && start - previousEnd > Math.max(3.0f * size, 24f)) {
                    segments.add(toSegment(piece));
                    piece = new ArrayList<>();
                }
                piece.add(word);
                TextPosition last = word.get(word.size() - 1);
                previousEnd = last.getXDirAdj() + last.getWidthDirAdj();
            }
            if (!piece.isEmpty()) {
                segments.add(toSegment(piece));
            }
            words.clear();
        }

        private static Segment toSegment(List<List<TextPosition>> piece) {
            StringBuilder text = new StringBuilder();
            float x0 = Float.MAX_VALUE;
            float x1 = 0;
            float y = 0;
            float height = 0;
            float size = 0;
            int chars = 0;
            int boldChars = 0;
            float letterPitch = letterSpacedPitch(piece);
            float previousStart = Float.NaN;
            for (List<TextPosition> word : piece) {
                float start = word.get(0).getXDirAdj();
                // Letter-box ("comb") values arrive one glyph per word:
                // glue the letters, keep a space only where a box was left empty.
                boolean glue = letterPitch > 0 && !Float.isNaN(previousStart)
                        && start - previousStart <= letterPitch * 1.5f;
                if (!text.isEmpty() && !glue) {
                    text.append(' ');
                }
                previousStart = start;
                for (TextPosition position : word) {
                    text.append(position.getUnicode());
                    x0 = Math.min(x0, position.getXDirAdj());
                    x1 = Math.max(x1, position.getXDirAdj() + position.getWidthDirAdj());
                    y = Math.max(y, position.getYDirAdj());
                    height = Math.max(height, position.getHeightDir());
                    size = Math.max(size, fontSize(position));
                    int length = position.getUnicode() == null ? 0 : position.getUnicode().strip().length();
                    chars += length;
                    if (isBold(position.getFont())) {
                        boldChars += length;
                    }
                }
            }
            return new Segment(text.toString().replaceAll("\\s+", " ").strip(), x0, x1, y,
                    height <= 0 ? size : height, size, chars > 0 && boldChars >= chars * 0.8, false);
        }

        /**
         * The typical start-to-start distance of a letter-spaced piece (most
         * "words" a single glyph, at least four of them), or 0 for normal text.
         */
        static float letterSpacedPitch(List<List<TextPosition>> piece) {
            if (piece.size() < 4) {
                return 0;
            }
            long singles = piece.stream().filter(w -> w.size() == 1).count();
            if (singles < piece.size() * 0.8) {
                return 0;
            }
            List<Float> pitches = new ArrayList<>();
            for (int i = 1; i < piece.size(); i++) {
                pitches.add(piece.get(i).get(0).getXDirAdj() - piece.get(i - 1).get(0).getXDirAdj());
            }
            pitches.sort(Float::compare);
            return pitches.get(pitches.size() / 4);
        }
    }

    private static float fontSize(TextPosition position) {
        float inPt = position.getFontSizeInPt();
        return inPt > 0 ? inPt : position.getFontSize();
    }

    static boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
        if (name.contains("bold") || name.contains("black") || name.contains("heavy")
                || name.contains("semibold") || name.contains("demi")) {
            return true;
        }
        PDFontDescriptor descriptor = font.getFontDescriptor();
        return descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600);
    }

    private static float pageWidth(PDPage page) {
        PDRectangle box = page.getCropBox();
        boolean rotated = page.getRotation() % 180 != 0;
        return rotated ? box.getHeight() : box.getWidth();
    }

    private static float pageHeight(PDPage page) {
        PDRectangle box = page.getCropBox();
        boolean rotated = page.getRotation() % 180 != 0;
        return rotated ? box.getWidth() : box.getHeight();
    }

    // ----------------------------------------------------------- form fields

    record FieldValue(String label, String value, float x0, float x1, float y, float height) {
    }

    /** Filled-in AcroForm values live in widget annotations, not in the page content. */
    private static Map<Integer, List<FieldValue>> formFields(PDDocument document) {
        Map<Integer, List<FieldValue>> byPage = new HashMap<>();
        try {
            PDAcroForm form = document.getDocumentCatalog().getAcroForm();
            if (form == null) {
                return byPage;
            }
            for (PDField field : form.getFieldTree()) {
                if (!(field instanceof PDTerminalField terminal)) {
                    continue;
                }
                String value = fieldValue(terminal);
                if (value == null) {
                    continue;
                }
                String label = firstNonBlank(terminal.getAlternateFieldName(), terminal.getPartialName());
                for (PDAnnotationWidget widget : terminal.getWidgets()) {
                    int pageIndex = pageOf(document, widget);
                    PDRectangle rect = widget.getRectangle();
                    if (pageIndex < 0 || rect == null) {
                        continue;
                    }
                    PDPage page = document.getPage(pageIndex);
                    PDRectangle crop = page.getCropBox();
                    float top = crop.getUpperRightY() - rect.getUpperRightY();
                    byPage.computeIfAbsent(pageIndex, k -> new ArrayList<>()).add(new FieldValue(
                            label == null ? "" : label.strip(), value,
                            rect.getLowerLeftX() - crop.getLowerLeftX(),
                            rect.getUpperRightX() - crop.getLowerLeftX(),
                            top + rect.getHeight() * 0.8f, rect.getHeight()));
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.info("PDF blueprint: form fields unreadable, continuing without them: {}", e.getMessage());
        }
        return byPage;
    }

    private static String fieldValue(PDTerminalField field) {
        String raw = field.getValueAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (field instanceof PDButton) {
            return "off".equalsIgnoreCase(value) ? null : "[x]";
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static int pageOf(PDDocument document, PDAnnotationWidget widget) {
        if (widget.getPage() != null) {
            int index = document.getPages().indexOf(widget.getPage());
            if (index >= 0) {
                return index;
            }
        }
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            try {
                if (document.getPage(i).getAnnotations().stream()
                        .anyMatch(a -> a.getCOSObject() == widget.getCOSObject())) {
                    return i;
                }
            } catch (IOException ignored) {
                // an unreadable annotation list just means "not on this page"
            }
        }
        return -1;
    }

    /**
     * Adds field values as segments at their widget position, unless the page
     * already prints that value there (a flattened or appearance-rendered form).
     * A value with no printed text on its row gets its field label as a prefix.
     */
    static List<Segment> mergeFields(List<Segment> segments, List<FieldValue> fields) {
        if (fields.isEmpty()) {
            return segments;
        }
        List<Segment> merged = new ArrayList<>(segments);
        for (FieldValue field : fields) {
            String needle = compact(field.value());
            boolean printed = segments.stream().anyMatch(s ->
                    Math.abs(s.y() - field.y()) <= Math.max(field.height(), s.height())
                            && s.x1() >= field.x0() - 2 && s.x0() <= field.x1() + 2
                            && compact(s.text()).contains(needle));
            if (printed) {
                continue;
            }
            boolean rowHasText = segments.stream().anyMatch(s ->
                    Math.abs(s.y() - field.y()) <= Math.max(field.height(), s.height()) * 0.75f);
            String text = rowHasText || field.label().isEmpty()
                    ? field.value() : field.label() + ": " + field.value();
            float size = Math.max(6f, Math.min(field.height() * 0.7f, 14f));
            merged.add(new Segment(text, field.x0(), field.x1(), field.y(), size, size, false, true));
        }
        return merged;
    }

    // --------------------------------------------------------- reading order

    /**
     * Rows of segments in top-to-bottom order; a page with a clear gutter
     * between two text columns is read column by column within each band
     * between full-width lines.
     */
    static List<Line> readingOrder(int pageIndex, List<Segment> segments, float pageWidth) {
        List<Segment> sorted = new ArrayList<>(segments.stream().filter(s -> !s.text().isBlank()).toList());
        sorted.sort((a, b) -> Float.compare(a.y(), b.y()) != 0 ? Float.compare(a.y(), b.y())
                : Float.compare(a.x0(), b.x0()));
        Float gutter = findGutter(sorted, pageWidth);
        if (gutter == null) {
            return rows(pageIndex, sorted);
        }
        List<Line> lines = new ArrayList<>();
        List<Segment> left = new ArrayList<>();
        List<Segment> right = new ArrayList<>();
        for (Segment segment : sorted) {
            boolean spans = segment.x0() < gutter && segment.x1() > gutter;
            if (spans) {
                lines.addAll(rows(pageIndex, left));
                lines.addAll(rows(pageIndex, right));
                left.clear();
                right.clear();
                lines.addAll(rows(pageIndex, List.of(segment)));
            } else if (segment.x1() <= gutter) {
                left.add(segment);
            } else {
                right.add(segment);
            }
        }
        lines.addAll(rows(pageIndex, left));
        lines.addAll(rows(pageIndex, right));
        return lines;
    }

    /**
     * A vertical line almost no segment crosses, with real prose (wide
     * segments) on both sides. Narrow segments on both sides are a form's
     * label/value grid, which must stay row by row.
     */
    static Float findGutter(List<Segment> segments, float pageWidth) {
        if (segments.size() < 12 || pageWidth <= 0) {
            return null;
        }
        Float best = null;
        int bestCrossing = Integer.MAX_VALUE;
        for (float x = pageWidth * 0.3f; x <= pageWidth * 0.7f; x += pageWidth / 100f) {
            int crossing = 0;
            List<Float> leftWidths = new ArrayList<>();
            List<Float> rightWidths = new ArrayList<>();
            for (Segment s : segments) {
                if (s.x0() < x && s.x1() > x) {
                    crossing++;
                } else if (s.x1() <= x) {
                    leftWidths.add(s.x1() - s.x0());
                } else {
                    rightWidths.add(s.x1() - s.x0());
                }
            }
            if (leftWidths.size() < 6 || rightWidths.size() < 6
                    || crossing > segments.size() * 0.10) {
                continue;
            }
            if (median(leftWidths) < pageWidth * 0.25f || median(rightWidths) < pageWidth * 0.25f) {
                continue;
            }
            if (crossing < bestCrossing) {
                bestCrossing = crossing;
                best = x;
            }
        }
        return best;
    }

    private static float median(List<Float> values) {
        List<Float> sorted = new ArrayList<>(values);
        sorted.sort(Float::compare);
        return sorted.get(sorted.size() / 2);
    }

    /** Segments sharing a baseline become one row; a row of several segments keeps them as cells. */
    private static List<Line> rows(int pageIndex, List<Segment> segments) {
        List<Line> lines = new ArrayList<>();
        List<Segment> row = new ArrayList<>();
        for (Segment segment : segments) {
            if (!row.isEmpty()) {
                Segment first = row.get(0);
                float tolerance = Math.max(2f, Math.min(first.height(), segment.height()) * 0.5f);
                if (Math.abs(segment.y() - first.y()) > tolerance) {
                    lines.add(toLine(pageIndex, row));
                    row = new ArrayList<>();
                }
            }
            row.add(segment);
        }
        if (!row.isEmpty()) {
            lines.add(toLine(pageIndex, row));
        }
        return lines;
    }

    private static Line toLine(int pageIndex, List<Segment> row) {
        List<Segment> ordered = new ArrayList<>(row);
        ordered.sort((a, b) -> Float.compare(a.x0(), b.x0()));
        List<String> cells = new ArrayList<>();
        float y = 0;
        float height = 0;
        float size = 0;
        boolean bold = true;
        for (Segment segment : ordered) {
            cells.add(segment.text());
            y = Math.max(y, segment.y());
            height = Math.max(height, segment.height());
            size = Math.max(size, segment.fontSize());
            bold &= segment.bold();
        }
        return new Line(pageIndex, cells, y, height, size, bold, false);
    }

    // ------------------------------------------------------------------- OCR

    /** Whether the page's printed content is mostly an image or outlined glyphs rather than text. */
    static boolean looksPrintedAsGraphics(PDPage page) {
        try {
            GraphicsProbe probe = new GraphicsProbe(page);
            probe.processPage(page);
            PDRectangle box = page.getCropBox();
            double pageArea = Math.max(1.0, (double) box.getWidth() * box.getHeight());
            return probe.imageArea / pageArea >= IMAGE_PAGE_SHARE || probe.fills >= OUTLINED_TEXT_FILLS;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Sums the area images are drawn at and counts path fills; draws nothing. */
    private static final class GraphicsProbe extends org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine {
        double imageArea;
        int fills;

        GraphicsProbe(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage image) {
            org.apache.pdfbox.util.Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            imageArea += Math.abs((double) ctm.getScalingFactorX() * ctm.getScalingFactorY());
        }

        @Override
        public void fillPath(int windingRule) {
            fills++;
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            fills++;
        }

        @Override
        public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1,
                                    java.awt.geom.Point2D p2, java.awt.geom.Point2D p3) {
        }

        @Override
        public void clip(int windingRule) {
        }

        @Override
        public void moveTo(float x, float y) {
        }

        @Override
        public void lineTo(float x, float y) {
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        }

        @Override
        public java.awt.geom.Point2D getCurrentPoint() {
            return new java.awt.geom.Point2D.Float();
        }

        @Override
        public void closePath() {
        }

        @Override
        public void endPath() {
        }

        @Override
        public void strokePath() {
        }

        @Override
        public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {
        }
    }

    private List<Line> ocrPage(PDDocument document, int pageIndex) {
        String text;
        try {
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageIndex, OCR_DPI, ImageType.GRAY);
            text = ocr.extractText(image);
        } catch (IOException | RuntimeException e) {
            log.info("PDF blueprint: page {} could not be OCR'd: {}", pageIndex + 1, e.getMessage());
            return List.of();
        }
        return ocrLines(pageIndex, text);
    }

    /** OCR output: one line per text line; blank lines become paragraph breaks (a larger y gap). */
    static List<Line> ocrLines(int pageIndex, String text) {
        List<Line> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        float y = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.replaceAll("\\s+", " ").strip();
            if (line.isEmpty()) {
                y += 36;
                continue;
            }
            y += 12;
            lines.add(new Line(pageIndex, List.of(line), y, 10, 0, false, true));
        }
        return lines;
    }

    // -------------------------------------------------------- running heads

    /** Text repeated in the top or bottom band of many pages, and bare page numbers there. */
    private static List<Line> dropRunningHeadersAndFooters(List<Line> lines, PDDocument document) {
        int pages = document.getNumberOfPages();
        Map<String, Set<Integer>> pagesByText = new HashMap<>();
        for (Line line : lines) {
            if (!line.ocr && inMargin(line, document, 0.05f)) {
                pagesByText.computeIfAbsent(runningKey(line.text()), k -> new HashSet<>()).add(line.page);
            }
        }
        List<Line> kept = new ArrayList<>();
        for (Line line : lines) {
            if (!line.ocr && inMargin(line, document, 0.08f)) {
                if (PAGE_NUMBER.matcher(line.text()).matches()) {
                    continue;
                }
                Set<Integer> seen = pagesByText.get(runningKey(line.text()));
                if (inMargin(line, document, 0.05f) && pages >= 3 && seen != null
                        && seen.size() >= 3 && seen.size() >= pages / 2) {
                    continue;
                }
            }
            kept.add(line);
        }
        return kept;
    }

    private static boolean inMargin(Line line, PDDocument document, float share) {
        float height = pageHeight(document.getPage(line.page));
        return height > 0 && (line.y <= height * share || line.y >= height * (1 - share));
    }

    private static String runningKey(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
    }

    // -------------------------------------------------------------- headings

    private static List<Bookmark> bookmarks(PDDocument document) {
        List<Bookmark> bookmarks = new ArrayList<>();
        try {
            PDOutlineNode outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                collectBookmarks(document, outline, 1, bookmarks);
            }
        } catch (RuntimeException e) {
            log.info("PDF blueprint: bookmarks unreadable, continuing without them: {}", e.getMessage());
        }
        return bookmarks;
    }

    private static void collectBookmarks(PDDocument document, PDOutlineNode node, int depth, List<Bookmark> out) {
        if (depth > 12 || out.size() > 2000) {
            return;
        }
        for (PDOutlineItem item : node.children()) {
            String title = normalize(item.getTitle());
            if (!title.isEmpty()) {
                int page = -1;
                try {
                    PDPage target = item.findDestinationPage(document);
                    page = target == null ? -1 : document.getPages().indexOf(target);
                } catch (IOException | RuntimeException ignored) {
                    // a bookmark without a resolvable page still matches by text
                }
                out.add(new Bookmark(title, depth, page));
            }
            collectBookmarks(document, item, depth + 1, out);
        }
    }

    /** A bookmark's title found on its page (or anywhere, when the page is unknown) marks a heading. */
    static void applyBookmarks(List<Line> lines, List<Bookmark> bookmarks) {
        Set<Bookmark> used = new HashSet<>();
        for (Line line : lines) {
            String text = normalize(line.text());
            if (text.isEmpty() || text.length() > MAX_HEADING_CHARS * 2) {
                continue;
            }
            for (Bookmark bookmark : bookmarks) {
                if (used.contains(bookmark) || (bookmark.page() >= 0 && bookmark.page() != line.page)) {
                    continue;
                }
                String title = bookmark.normalizedTitle();
                boolean matches = text.equals(title)
                        || (title.length() >= 4 && text.startsWith(title) && text.length() <= title.length() + 40)
                        || (text.length() >= 4 && title.startsWith(text) && text.length() >= title.length() * 0.6);
                if (matches) {
                    line.level = Math.min(bookmark.depth(), MAX_HEADING_LEVEL);
                    line.strongHeading = true;
                    used.add(bookmark);
                    break;
                }
            }
        }
    }

    /** Tagged PDFs mark headings as H / H1..H6 marked content; text → level for this page. */
    private static Map<String, Integer> taggedHeadings(PDPage page) {
        Map<String, Integer> levels = new HashMap<>();
        try {
            PDFMarkedContentExtractor extractor = new PDFMarkedContentExtractor();
            extractor.processPage(page);
            for (PDMarkedContent content : extractor.getMarkedContents()) {
                collectTagged(content, levels);
            }
        } catch (IOException | RuntimeException ignored) {
            // untagged or unusual content: no tag hints
        }
        return levels;
    }

    private static void collectTagged(PDMarkedContent content, Map<String, Integer> levels) {
        String tag = content.getTag() == null ? "" : content.getTag();
        Matcher matcher = HEADING_TAG.matcher(tag);
        if (matcher.matches()) {
            StringBuilder text = new StringBuilder();
            appendText(content, text);
            String normalized = normalize(text.toString());
            if (!normalized.isEmpty()) {
                int level = matcher.group(1) == null ? 1 : Integer.parseInt(matcher.group(1));
                levels.putIfAbsent(normalized, level);
            }
        }
        for (Object child : content.getContents()) {
            if (child instanceof PDMarkedContent nested) {
                collectTagged(nested, levels);
            }
        }
    }

    private static void appendText(PDMarkedContent content, StringBuilder out) {
        for (Object child : content.getContents()) {
            if (child instanceof TextPosition position) {
                out.append(position.getUnicode());
            } else if (child instanceof PDMarkedContent nested) {
                appendText(nested, out);
            }
        }
    }

    /**
     * Font-size tiers first (sizes clearly above the body size, largest = 1),
     * then textual cues one level below the deepest size tier. Lines already
     * marked by a bookmark or tag keep their level.
     */
    static void classifyHeadings(List<Line> lines) {
        float body = bodyFontSize(lines);
        TreeMap<Float, Integer> tiers = new TreeMap<>();
        if (body > 0) {
            for (Line line : lines) {
                if (line.fontSize >= body * HEADING_SIZE_RATIO && headingShaped(line)) {
                    tiers.put(roundSize(line.fontSize), 0);
                }
            }
            int rank = 1;
            for (Float size : tiers.descendingKeySet()) {
                tiers.put(size, Math.min(rank++, 3));
            }
        }
        int cueLevel = Math.min((tiers.isEmpty() ? 0 : tiers.values().stream().max(Integer::compare).orElse(0)) + 1,
                MAX_HEADING_LEVEL);
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.strongHeading) {
                continue;
            }
            if (body > 0 && line.fontSize >= body * HEADING_SIZE_RATIO && headingShaped(line)) {
                line.level = tiers.getOrDefault(roundSize(line.fontSize), 1);
                line.strongHeading = true;
                continue;
            }
            Line next = i + 1 < lines.size() ? lines.get(i + 1) : null;
            line.level = textualCueLevel(line, next, cueLevel);
        }
    }

    private static boolean headingShaped(Line line) {
        String text = line.text();
        return !line.isRow() && !text.isEmpty() && text.length() <= MAX_HEADING_CHARS
                && letterCount(text) >= 2;
    }

    /** The heading level a line's text earns on its own, or 0. */
    static int textualCueLevel(Line line, Line next, int cueLevel) {
        String text = line.text();
        if (line.isRow() || text.length() > MAX_CUE_CHARS || letterCount(text) < 2) {
            return 0;
        }
        boolean sentenceEnd = text.endsWith(".") || text.endsWith(";") || text.endsWith(",");
        boolean endsWithDigit = Character.isDigit(text.charAt(text.length() - 1));
        Matcher decimal = DECIMAL_NUMBERING.matcher(text);
        if (decimal.find() && !sentenceEnd && !endsWithDigit) {
            int depth = decimal.group(1).split("\\.").length;
            return Math.min(depth, MAX_HEADING_LEVEL);
        }
        if (KEYWORD_PREFIX.matcher(text).find() && text.length() <= 60) {
            return 1;
        }
        if (!sentenceEnd && ROMAN_NUMBERING.matcher(text).find()) {
            return Math.min(1, MAX_HEADING_LEVEL);
        }
        if (!sentenceEnd && LETTER_NUMBERING.matcher(text).find()) {
            return Math.min(2, MAX_HEADING_LEVEL);
        }
        if (!sentenceEnd && PAREN_NUMBERING.matcher(text).find() && text.length() <= 60) {
            return Math.min(3, MAX_HEADING_LEVEL);
        }
        if (isAllCaps(text) && !sentenceEnd) {
            return cueLevel;
        }
        if (line.bold && !line.ocr && !sentenceEnd) {
            return cueLevel;
        }
        if (text.endsWith(":") && text.length() <= 50 && next != null && next.page == line.page && next.isRow()) {
            return cueLevel;
        }
        return 0;
    }

    private static boolean isAllCaps(String text) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        return letters >= 4 && upper >= letters * 0.9;
    }

    private static int letterCount(String text) {
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                letters++;
            }
        }
        return letters;
    }

    /** The most common font size by character count; 0 when nothing carries a size (OCR only). */
    static float bodyFontSize(List<Line> lines) {
        Map<Float, Integer> chars = new HashMap<>();
        for (Line line : lines) {
            if (line.fontSize > 0) {
                chars.merge(roundSize(line.fontSize), line.text().length(), Integer::sum);
            }
        }
        return chars.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0f);
    }

    private static float roundSize(float size) {
        return Math.round(size * 2) / 2f;
    }

    // ------------------------------------------------------------ paragraphs

    /** Consecutive body lines of one style and spacing become one paragraph; headings and rows stand alone. */
    static List<Paragraph> buildParagraphs(List<Line> lines) {
        List<Paragraph> paragraphs = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        for (Line line : lines) {
            if (!current.isEmpty() && !continues(current.get(current.size() - 1), line)) {
                paragraphs.add(toParagraph(current));
                current = new ArrayList<>();
            }
            current.add(line);
            if (line.level > 0 || line.isRow()) {
                paragraphs.add(toParagraph(current));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            paragraphs.add(toParagraph(current));
        }
        return paragraphs.stream().filter(p -> !p.text().isEmpty()).toList();
    }

    private static boolean continues(Line previous, Line line) {
        if (line.page != previous.page || line.level > 0 || line.isRow() || previous.level > 0) {
            return false;
        }
        if (previous.bold != line.bold || Math.abs(previous.fontSize - line.fontSize) > 0.5f) {
            return false;
        }
        String previousText = previous.text();
        if (previousText.endsWith(":")) {
            return false;
        }
        float gap = line.y - previous.y;
        // Glyph heights from PDFBox are often well under the font size, so
        // the font size bounds the line pitch too (normal leading ≈ 1.2×).
        float lineHeight = Math.max(Math.max(previous.height, line.height),
                Math.max(previous.fontSize, line.fontSize));
        return gap > 0 && gap <= Math.max(lineHeight, 1f) * 1.8f;
    }

    private static Paragraph toParagraph(List<Line> lines) {
        Line first = lines.get(0);
        if (lines.size() == 1 && first.isRow()) {
            return new Paragraph(List.copyOf(first.cells), first.level);
        }
        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            String piece = line.text();
            if (text.length() > 0) {
                boolean hyphenated = text.charAt(text.length() - 1) == '-'
                        && !piece.isEmpty() && Character.isLowerCase(piece.charAt(0));
                if (hyphenated) {
                    text.setLength(text.length() - 1);
                } else {
                    text.append(' ');
                }
            }
            text.append(piece);
        }
        return new Paragraph(List.of(text.toString()), first.level);
    }

    /**
     * When headings swamp the document (a form where every bold label is a
     * cue), keep only the strong ones (bookmarks, tags, font size) — or, with
     * none of those, the cues that are not just bold.
     */
    private static List<Paragraph> limitWeakHeadings(List<Paragraph> paragraphs, List<Line> lines) {
        long headings = paragraphs.stream().filter(p -> p.level() > 0).count();
        if (paragraphs.size() < 10 || headings <= paragraphs.size() * MAX_HEADING_SHARE) {
            return paragraphs;
        }
        Set<String> strong = new HashSet<>();
        for (Line line : lines) {
            if (line.strongHeading && line.level > 0) {
                strong.add(line.text());
            }
        }
        List<Paragraph> limited = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            boolean keep = paragraph.level() == 0 || strong.contains(paragraph.text())
                    || (strong.isEmpty() && structuralCue(paragraph.text()));
            limited.add(keep ? paragraph : new Paragraph(paragraph.cells(), 0));
        }
        return limited;
    }

    private static boolean structuralCue(String text) {
        return DECIMAL_NUMBERING.matcher(text).find() || KEYWORD_PREFIX.matcher(text).find()
                || ROMAN_NUMBERING.matcher(text).find() || isAllCaps(text);
    }

    // ----------------------------------------------------------------- write

    private static byte[] writeDocx(List<Paragraph> paragraphs) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (Paragraph paragraph : paragraphs) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                List<String> cells = paragraph.cells();
                for (int i = 0; i < cells.size(); i++) {
                    if (i > 0) {
                        run.addTab();
                    }
                    run.setText(cells.get(i), i);
                }
                if (paragraph.level() > 0) {
                    int level = Math.min(paragraph.level(), MAX_HEADING_LEVEL);
                    p.setStyle("Heading" + level);
                    // The direct outline level is what Word's navigation and the
                    // section parser read first; it works without a styles part.
                    var ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
                    ppr.addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
                    run.setBold(true);
                    run.setFontSize(HEADING_HALF_POINTS[level - 1] / 2.0);
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the Word version of the PDF.", e);
        }
    }

    // --------------------------------------------------------------- helpers

    private static String joinText(List<Segment> segments) {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            text.append(segment.text()).append('\n');
        }
        return text.toString();
    }

    static int visibleChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** Fonts without a Unicode map extract as replacement, private-use or control characters. */
    static boolean garbled(String text) {
        int visible = 0;
        int junk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            visible++;
            int type = Character.getType(c);
            if (c == '\uFFFD' || type == Character.PRIVATE_USE || type == Character.CONTROL
                    || type == Character.UNASSIGNED || type == Character.SURROGATE) {
                junk++;
            }
        }
        return visible >= MIN_TEXT_CHARS && junk > visible * GARBLED_RATIO;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").strip();
    }

    private static String compact(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
