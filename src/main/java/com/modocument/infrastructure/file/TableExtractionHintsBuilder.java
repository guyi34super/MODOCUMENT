package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableExtractionHints;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds {@link TableExtractionHints} from the source file and extracted text. */
@Component
public class TableExtractionHintsBuilder {

    private static final Pattern ROW_LINE = Pattern.compile("^Row\\s+\\d+:");

    public TableExtractionHints build(Path file, String extractedText, String extractionSource) {
        String format = extensionOf(file);
        TableExtractionHints fromMarkdown = hintsFromMarkdown(extractedText, format, extractionSource);
        if (fromMarkdown.expectedColumnCount() > 0) {
            return fromMarkdown;
        }
        TableExtractionHints fromUnstructured = hintsFromUnstructuredText(extractedText, format, extractionSource);
        if (fromUnstructured.expectedColumnCount() > 0) {
            return fromUnstructured;
        }
        return switch (format) {
            case ".csv" -> hintsFromCsv(file, extractionSource);
            case ".xlsx", ".xls", ".xlsm" -> hintsFromExcel(file, extractionSource);
            default -> TableExtractionHints.empty(format, extractionSource);
        };
    }

    private static TableExtractionHints hintsFromMarkdown(String text, String format,
                                                          String extractionSource) {
        if (text == null || text.isBlank()) {
            return TableExtractionHints.empty(format, extractionSource);
        }
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("|") || !line.endsWith("|")) {
                continue;
            }
            if (isSeparatorRow(line)) {
                continue;
            }
            List<String> headers = parseMarkdownRow(line);
            if (headers.isEmpty()) {
                continue;
            }
            int dataRows = 0;
            for (int j = i + 1; j < lines.size(); j++) {
                String row = lines.get(j).trim();
                if (!row.startsWith("|")) {
                    break;
                }
                if (isSeparatorRow(row)) {
                    continue;
                }
                dataRows++;
            }
            return new TableExtractionHints(
                    format, extractionSource, headers.size(), headers, dataRows);
        }
        return TableExtractionHints.empty(format, extractionSource);
    }

    private static TableExtractionHints hintsFromUnstructuredText(String text, String format,
                                                                 String extractionSource) {
        if (text == null || text.isBlank()) {
            return TableExtractionHints.empty(format, extractionSource);
        }
        List<String> rowLines = text.lines()
                .map(String::trim)
                .filter(line -> ROW_LINE.matcher(line).find())
                .toList();
        if (rowLines.size() >= 2) {
            String headerLine = rowLines.get(0);
            int tabCount = countTabsAfterColon(headerLine) + 1;
            List<String> headers = parseTabRow(headerLine, tabCount);
            return new TableExtractionHints(
                    format, extractionSource, tabCount, headers, rowLines.size() - 1);
        }
        List<String> lines = text.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.contains("\t") && !line.contains(",")) {
                continue;
            }
            char delimiter = line.contains("\t") ? '\t' : ',';
            String[] headerParts = line.split(delimiter == '\t' ? "\t" : ",", -1);
            if (headerParts.length < 2) {
                continue;
            }
            List<String> headers = new ArrayList<>();
            for (String part : headerParts) {
                headers.add(part.trim());
            }
            int dataRows = 0;
            for (int j = i + 1; j < lines.size(); j++) {
                String row = lines.get(j);
                if (!row.contains(String.valueOf(delimiter))) {
                    break;
                }
                dataRows++;
            }
            if (dataRows > 0) {
                return new TableExtractionHints(
                        format, extractionSource, headers.size(), headers, dataRows);
            }
        }
        return TableExtractionHints.empty(format, extractionSource);
    }

    private static int countTabsAfterColon(String rowLine) {
        int colon = rowLine.indexOf(':');
        if (colon < 0) {
            return 0;
        }
        String cells = rowLine.substring(colon + 1);
        if (cells.isBlank()) {
            return 0;
        }
        return (int) cells.chars().filter(ch -> ch == '\t').count();
    }

    private static List<String> parseTabRow(String rowLine, int expectedColumns) {
        int colon = rowLine.indexOf(':');
        String cells = colon >= 0 ? rowLine.substring(colon + 1).trim() : rowLine;
        String[] parts = cells.split("\t", -1);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < expectedColumns; i++) {
            headers.add(i < parts.length ? parts[i].trim() : "");
        }
        return headers;
    }

    private static TableExtractionHints hintsFromCsv(Path file, String extractionSource) {
        try (Reader reader = Files.newBufferedReader(file);
             CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT)) {
            List<String> headers = List.of();
            int dataRows = 0;
            boolean headerSeen = false;
            for (CSVRecord record : parser) {
                if (!headerSeen) {
                    headerSeen = true;
                    List<String> parsed = new ArrayList<>();
                    record.forEach(cell -> parsed.add(cell == null ? "" : cell.trim()));
                    headers = parsed;
                    continue;
                }
                dataRows++;
            }
            if (!headers.isEmpty()) {
                return new TableExtractionHints(
                        ".csv", extractionSource, headers.size(), headers, dataRows);
            }
        } catch (IOException e) {
            return TableExtractionHints.empty(".csv", extractionSource);
        }
        return TableExtractionHints.empty(".csv", extractionSource);
    }

    private static TableExtractionHints hintsFromExcel(Path file, String extractionSource) {
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = TabularNormalizer.primarySheet(workbook);
            if (sheet == null) {
                return TableExtractionHints.empty(".xlsx", extractionSource);
            }
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<Long, String> merged = TabularNormalizer.mergedRegionValues(sheet, formatter, evaluator);
            int width = TabularNormalizer.sheetWidth(sheet);
            if (width <= 0) {
                return TableExtractionHints.empty(extensionOf(file), extractionSource);
            }
            int headerRowIndex = ExcelHeaderRowDetector.detectHeaderRowIndex(
                    sheet, width, merged, formatter, evaluator);
            List<String> headers = ExcelHeaderRowDetector.readRowValues(
                    sheet, headerRowIndex, width, merged, formatter, evaluator);
            int dataRows = 0;
            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getPhysicalNumberOfCells() > 0) {
                    dataRows++;
                }
            }
            return new TableExtractionHints(
                    extensionOf(file), extractionSource, headers.size(), headers, dataRows);
        } catch (IOException e) {
            return TableExtractionHints.empty(".xlsx", extractionSource);
        }
    }

    private static List<String> parseMarkdownRow(String line) {
        String trimmed = line.substring(1, line.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private static boolean isSeparatorRow(String line) {
        return line.matches("\\|\\s*[-: ]+\\s*(\\|\\s*[-: ]+\\s*)+\\|");
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
