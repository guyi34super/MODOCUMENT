package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic (no-LLM) normalization of structured files into {@link TableData}:
 * csv via Commons CSV, xlsx/xls via POI (primary sheet, merged cells filled).
 */
@Component
public class TabularNormalizer {

    private static final Set<String> EXTENSIONS = Set.of(".csv", ".xlsx", ".xls", ".xlsm");

    public boolean supports(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(ext);
    }

    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    public TableData normalize(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".csv")) {
                return readCsv(file);
            }
            if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".xlsm")) {
                return readExcel(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file: " + file, e);
        }
        throw new UnsupportedOperationException(
                "TabularNormalizer does not handle '" + name + "'.");
    }

    private static TableData readCsv(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file);
             CSVParser parser = CSVParser.parse(reader, CSVFormat.DEFAULT)) {
            List<String> columns = List.of();
            List<Map<String, String>> rows = new ArrayList<>();
            boolean headerSeen = false;
            for (CSVRecord record : parser) {
                if (!headerSeen) {
                    headerSeen = true;
                    List<String> rawHeaders = new ArrayList<>(record.size());
                    record.forEach(rawHeaders::add);
                    columns = uniqueColumns(rawHeaders);
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    row.put(columns.get(i), i < record.size() ? record.get(i) : "");
                }
                rows.add(row);
            }
            return new TableData(columns, rows);
        }
    }

    static List<String> uniqueColumns(List<String> headers) {
        List<String> columns = new ArrayList<>(headers.size());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i) == null ? "" : headers.get(i);
            if (i == 0) {
                header = header.replace("\uFEFF", "");
            }
            header = header.trim();
            if (header.isBlank()) {
                header = "Column" + (i + 1);
            }
            int count = seen.merge(header, 1, Integer::sum);
            columns.add(count == 1 ? header : header + " (" + count + ")");
        }
        return columns;
    }

    private static TableData readExcel(Path file) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = primarySheet(workbook);
            if (sheet == null) {
                return new TableData(List.of(), List.of());
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<Long, String> merged = mergedRegionValues(sheet, formatter, evaluator);

            List<String> columns = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();

            int width = sheetWidth(sheet);
            if (width <= 0) {
                return new TableData(columns, rows);
            }
            int headerRowIndex = ExcelHeaderRowDetector.detectHeaderRowIndex(
                    sheet, width, merged, formatter, evaluator);
            Row headerRow = sheet.getRow(headerRowIndex);
            if (headerRow == null) {
                return new TableData(columns, rows);
            }
            List<String> rawHeaders = ExcelHeaderRowDetector.readRowValues(
                    sheet, headerRowIndex, width, merged, formatter, evaluator);
            columns.addAll(uniqueColumns(rawHeaders));

            for (int r = headerRowIndex + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                Map<String, String> values = new LinkedHashMap<>();
                boolean anyValue = false;
                for (int c = 0; c < columns.size(); c++) {
                    String value = merged.get(cellKey(r, c));
                    if (value == null) {
                        value = cellText(row == null ? null : row.getCell(c), formatter, evaluator);
                    }
                    anyValue |= value != null && !value.isBlank();
                    values.put(columns.get(c), value == null ? "" : value);
                }
                if (anyValue) {
                    rows.add(values);
                }
            }
            return new TableData(columns, rows);
        }
    }

    static int sheetWidth(Sheet sheet) {
        int width = 0;
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null) {
                width = Math.max(width, row.getLastCellNum());
            }
        }
        return width;
    }

    static Sheet primarySheet(Workbook workbook) {
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

    static Map<Long, String> mergedRegionValues(Sheet sheet, DataFormatter formatter,
                                                FormulaEvaluator evaluator) {
        Map<Long, String> values = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            Row topRow = sheet.getRow(region.getFirstRow());
            String value = cellText(topRow == null ? null : topRow.getCell(region.getFirstColumn()),
                    formatter, evaluator);
            if (value == null || value.isBlank()) {
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

    static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            return formatter.formatCellValue(cell);
        }
    }

    static long cellKey(int row, int col) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }
}
