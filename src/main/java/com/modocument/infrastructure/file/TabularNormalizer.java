package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic (no-LLM) normalization of structured files into {@link TableData}:
 * csv via Commons CSV, xlsx/xls via POI (first sheet, first row = header).
 * Other formats are not handled here — the ingestion pipeline sends their
 * extracted text through the LLM instead.
 */
@Component
public class TabularNormalizer {

    public boolean supports(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return ext.equals(".csv") || ext.equals(".xlsx") || ext.equals(".xls");
    }

    public TableData normalize(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".csv")) {
                return readCsv(file);
            }
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return readExcel(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file: " + file, e);
        }
        throw new UnsupportedOperationException(
                "TabularNormalizer does not handle '" + name + "'.");
    }

    private static TableData readCsv(Path file) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();
        try (Reader reader = Files.newBufferedReader(file);
             CSVParser parser = CSVParser.parse(reader, format)) {
            List<String> columns = new ArrayList<>(parser.getHeaderNames());
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    row.put(columns.get(i), i < record.size() ? record.get(i) : "");
                }
                rows.add(row);
            }
            return new TableData(columns, rows);
        }
    }

    private static TableData readExcel(Path file) throws IOException {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> columns = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return new TableData(columns, rows);
            }
            int lastCell = headerRow.getLastCellNum();
            for (int c = 0; c < lastCell; c++) {
                Cell cell = headerRow.getCell(c);
                String header = cell == null ? "" : formatter.formatCellValue(cell).trim();
                columns.add(header.isEmpty() ? "Column" + (c + 1) : header);
            }

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean anyValue = false;
                for (int c = 0; c < columns.size(); c++) {
                    Cell cell = row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    anyValue |= !value.isBlank();
                    values.put(columns.get(c), value);
                }
                if (anyValue) {
                    rows.add(values);
                }
            }
            return new TableData(columns, rows);
        }
    }
}
