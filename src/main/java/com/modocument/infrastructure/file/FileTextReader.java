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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
import java.util.Locale;

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
                case ".xlsx", ".xls" -> readExcel(path, Integer.MAX_VALUE, Integer.MAX_VALUE);
                case ".csv" -> Files.readString(Path.of(path));
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
                case ".xlsx", ".xls" -> readExcel(path, 5, 10);
                case ".csv" -> readCsvSnippet(path, 5);
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

    private static String readExcel(String path, int maxRows, int maxCellsPerRow) throws IOException {
        StringBuilder sb = new StringBuilder();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new File(path), null, true)) {
            Sheet sheet = workbook.getSheetAt(0);
            int rows = 0;
            for (Row row : sheet) {
                if (rows++ >= maxRows) {
                    break;
                }
                int cells = 0;
                for (Cell cell : row) {
                    if (cells++ >= maxCellsPerRow) {
                        break;
                    }
                    sb.append(formatter.formatCellValue(cell)).append('\t');
                }
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
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
