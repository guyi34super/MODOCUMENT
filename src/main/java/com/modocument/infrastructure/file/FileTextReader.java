package com.modocument.infrastructure.file;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.pdfbox.cos.COSName;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads xlsx/csv/pdf/doc(x) files and standalone images into plain text for
 * LLM prompts: full reads for extraction and small snippets for template
 * classification. Pages with no extractable text and standalone images go
 * through the OCR chain: vision LLM first (when enabled), then Tesseract.
 */
@Component
public class FileTextReader {

    private final OcrService ocrService;
    private final VisionOcrService visionOcrService;

    public FileTextReader(OcrService ocrService, VisionOcrService visionOcrService) {
        this.ocrService = ocrService;
        this.visionOcrService = visionOcrService;
    }

    /** Full text of the file, used to build the extraction prompt. */
    public String readAsText(String path) {
        String extension = extensionOf(path);
        try {
            return switch (extension) {
                case ".xlsx", ".xls", ".xlsm" -> readExcel(path, Integer.MAX_VALUE, Integer.MAX_VALUE);
                case ".csv" -> Files.readString(Path.of(path));
                case ".txt", ".text" -> Files.readString(Path.of(path));
                case ".pdf" -> readPdf(path, Integer.MAX_VALUE);
                case ".docx" -> readDocx(path);
                case ".doc" -> readDoc(path);
                case ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp" -> readImage(path);
                default -> throw new UnsupportedOperationException(
                        "File type '" + extension + "' is not supported.");
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file: " + path, e);
        }
    }

    /** Header + first rows/pages, truncated to maxChars, for the classifier prompt. */
    public String readSnippet(String path, int maxChars) {
        String extension = extensionOf(path);
        String text;
        try {
            text = switch (extension) {
                case ".xlsx", ".xls", ".xlsm" -> readExcel(path, 5, 10);
                case ".csv" -> readCsvSnippet(path, 5);
                case ".txt", ".text" -> Files.readString(Path.of(path));
                case ".pdf" -> readPdf(path, 2);
                case ".docx" -> readDocx(path);
                case ".doc" -> readDoc(path);
                case ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp" -> readImage(path);
                default -> throw new UnsupportedOperationException(
                        "Unsupported file type: " + extension);
            };
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file: " + path, e);
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private static String extensionOf(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * Serializes a workbook into a row-numbered grid for the LLM. Unlike the
     * deterministic {@code TabularNormalizer}, this keeps the full width, fills
     * merged regions (so multi-column/merged headers and label cells carry their
     * value across every covered cell), and evaluates formulas (so formula
     * columns show their computed value, not a cached/blank string). The LLM
     * then locates the real header among any title/blank rows.
     */
    private static String readExcel(String path, int maxRows, int maxCellsPerRow) throws IOException {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new File(path), null, true)) {
            Sheet sheet = primarySheet(workbook);
            if (sheet == null) {
                return "";
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<Long, String> merged = mergedRegionValues(sheet, formatter, evaluator);

            int first = sheet.getFirstRowNum();
            int last = sheet.getLastRowNum();
            int width = 0;
            for (int r = first; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    width = Math.max(width, row.getLastCellNum());
                }
            }
            width = Math.min(width, maxCellsPerRow);

            int emitted = 0;
            for (int r = first; r <= last && emitted < maxRows; r++, emitted++) {
                Row row = sheet.getRow(r);
                sb.append("Row ").append(r + 1).append(": ");
                for (int c = 0; c < width; c++) {
                    String value = merged.get(cellKey(r, c));
                    if (value == null) {
                        value = cellText(row == null ? null : row.getCell(c), formatter, evaluator);
                    }
                    sb.append(value);
                    if (c < width - 1) {
                        sb.append('\t');
                    }
                }
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /** The sheet with the most rows — the data table, rather than blindly sheet 0. */
    private static Sheet primarySheet(Workbook workbook) {
        Sheet best = null;
        int bestRows = -1;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            int rows = sheet.getPhysicalNumberOfRows();
            if (rows > bestRows) {
                bestRows = rows;
                best = sheet;
            }
        }
        return best;
    }

    /** Maps every cell covered by a merged region to that region's top-left value. */
    private static Map<Long, String> mergedRegionValues(Sheet sheet, DataFormatter formatter,
                                                        FormulaEvaluator evaluator) {
        Map<Long, String> values = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            Row topRow = sheet.getRow(region.getFirstRow());
            String value = cellText(topRow == null ? null : topRow.getCell(region.getFirstColumn()),
                    formatter, evaluator);
            if (value.isBlank()) {
                continue;
            }
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    values.put(cellKey(r, c), value);
                }
            }
        }
        return values;
    }

    /** Formatted cell value with formulas evaluated; falls back to the cached value on error. */
    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            // Unsupported/broken formula: use POI's cached display value instead.
            return formatter.formatCellValue(cell);
        }
    }

    private static long cellKey(int row, int column) {
        return ((long) row << 20) | (column & 0xFFFFF);
    }

    private static String readCsvSnippet(String path, int maxRecords) throws IOException {
        StringBuilder sb = new StringBuilder();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        try (Reader reader = Files.newBufferedReader(Path.of(path));
             CSVParser parser = CSVParser.parse(reader, format)) {
            sb.append(String.join("\t", parser.getHeaderNames())).append(System.lineSeparator());
            int count = 0;
            for (CSVRecord record : parser) {
                if (count++ >= maxRecords) {
                    break;
                }
                sb.append(String.join("\t", record.toList())).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private String readPdf(String path, int maxPages) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(new File(path))) {
            int pages = Math.min(document.getNumberOfPages(), maxPages);
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            int visionPagesUsed = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).trim();
                if (!pageText.isEmpty()) {
                    sb.append(pageText).append(System.lineSeparator());
                    continue;
                }
                // Scanned page: rasterize the whole page for the vision model
                // (captures multi-image/vector scans the per-image path misses),
                // bounded by the per-document vision page budget.
                String ocrText = "";
                if (visionOcrService.isEnabled()
                        && visionPagesUsed < visionOcrService.maxPagesPerDocument()) {
                    visionPagesUsed++;
                    ocrText = visionOcrService.extractText(
                            renderer.renderImageWithDPI(page - 1, visionOcrService.renderDpi()));
                }
                if (!ocrText.isBlank()) {
                    sb.append(ocrText).append(System.lineSeparator());
                } else {
                    sb.append(ocrPageImages(document.getPage(page - 1)));
                }
            }
        }
        return sb.toString();
    }

    private static String readDocx(String path) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(path));
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private static String readDoc(String path) throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(path));
             HWPFDocument document = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /** Standalone scans/photos are OCRed as a whole (empty when OCR is unavailable). */
    private String readImage(String path) throws IOException {
        BufferedImage image = ImageIO.read(new File(path));
        if (image == null) {
            throw new IOException("Could not decode image: " + path);
        }
        String text = visionOcrService.extractText(image);
        return !text.isBlank() ? text : ocrService.extractText(image);
    }

    private String ocrPageImages(PDPage page) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (page.getResources() == null) {
            return "";
        }
        for (COSName name : page.getResources().getXObjectNames()) {
            PDXObject xObject = page.getResources().getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                String text = ocrService.extractText(image.getImage());
                if (!text.isBlank()) {
                    sb.append(text).append(System.lineSeparator());
                }
            }
        }
        return sb.toString();
    }
}
