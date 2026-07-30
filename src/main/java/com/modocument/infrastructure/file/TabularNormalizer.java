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
import java.util.Set;

/**
 * Deterministic (no-LLM) normalization of structured files into {@link TableData}:
 * csv via Commons CSV, xlsx/xls via POI (first sheet, first row = header).
 * Other formats are not handled here — the ingestion pipeline sends their
 * extracted text through the LLM instead.
 */
@Component
public class TabularNormalizer {

    private static final Set<String> EXTENSIONS = Set.of(".csv", ".xlsx", ".xls");

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
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return readExcel(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file: " + file, e);
        }
        throw new UnsupportedOperationException(
                "TabularNormalizer does not handle '" + name + "'.");
    }

    /**
     * The first record is taken as the header by hand rather than through
     * {@code CSVFormat.setHeader()}: commons-csv rejects a blank header name
     * outright, which turns an otherwise readable export into a cryptic parse
     * failure. Naming those columns is {@link #uniqueColumns}'s job.
     */
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

    /**
     * Rows are keyed by column name, so repeated headers would collapse onto
     * one key and silently drop a column's data. A leading byte-order mark
     * (Excel writes one by default) is stripped for the same reason: it makes
     * the first column name look identical while never matching a template
     * field.
     */
    private static List<String> uniqueColumns(List<String> headers) {
        List<String> columns = new ArrayList<>(headers.size());
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i) == null ? "" : headers.get(i);
            if (i == 0) {
                header = header.replace("﻿", "");
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
            Sheet sheet = workbook.getSheetAt(0);
            List<String> columns = new ArrayList<>();
            List<Map<String, String>> rows = new ArrayList<>();

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return new TableData(columns, rows);
            }
            int lastCell = headerRow.getLastCellNum();
            List<String> rawHeaders = new ArrayList<>();
            for (int c = 0; c < lastCell; c++) {
                Cell cell = headerRow.getCell(c);
                rawHeaders.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
            }
            columns.addAll(uniqueColumns(rawHeaders));

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
