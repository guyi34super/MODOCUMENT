package com.modocument.infrastructure.file;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds the real header row in Excel sheets that start with title banners or blank rows. */
final class ExcelHeaderRowDetector {

    private static final int SCAN_LIMIT = 15;

    private ExcelHeaderRowDetector() {
    }

    static int detectHeaderRowIndex(Sheet sheet, int width, Map<Long, String> merged,
                                    DataFormatter formatter, FormulaEvaluator evaluator) {
        int first = sheet.getFirstRowNum();
        int last = Math.min(sheet.getLastRowNum(), first + SCAN_LIMIT - 1);
        int bestRow = first;
        int bestScore = Integer.MIN_VALUE;
        for (int r = first; r <= last; r++) {
            int score = scoreHeaderRow(sheet, r, width, merged, formatter, evaluator);
            if (score > bestScore) {
                bestScore = score;
                bestRow = r;
            }
        }
        return bestRow;
    }

    static List<String> readRowValues(Sheet sheet, int rowIndex, int width,
                                    Map<Long, String> merged,
                                    DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> values = new ArrayList<>(width);
        Row row = sheet.getRow(rowIndex);
        for (int c = 0; c < width; c++) {
            String value = merged.get(TabularNormalizer.cellKey(rowIndex, c));
            if (value == null) {
                value = TabularNormalizer.cellText(row == null ? null : row.getCell(c),
                        formatter, evaluator);
            }
            values.add(value == null ? "" : value.trim());
        }
        return values;
    }

    private static int scoreHeaderRow(Sheet sheet, int rowIndex, int width,
                                      Map<Long, String> merged,
                                      DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> cells = readRowValues(sheet, rowIndex, width, merged, formatter, evaluator);
        int filled = 0;
        int textLike = 0;
        int numericLike = 0;
        int totalLen = 0;
        Set<String> distinct = new HashSet<>();
        for (String cell : cells) {
            if (cell == null || cell.isBlank()) {
                continue;
            }
            filled++;
            distinct.add(cell.toLowerCase(Locale.ROOT));
            totalLen += cell.length();
            if (looksNumeric(cell)) {
                numericLike++;
            } else {
                textLike++;
            }
        }
        if (filled < 2) {
            return -1_000;
        }
        // Merged title banners repeat one long label across many columns.
        if (distinct.size() == 1 && filled >= 2) {
            return -800;
        }
        int avgLen = totalLen / filled;
        if (avgLen > 48) {
            return -600;
        }

        int score = filled * 12 + textLike * 4 - numericLike * 8;
        if (avgLen <= 24) {
            score += 20;
        }
        if (filled >= 3) {
            score += 15;
        }

        List<String> next = rowIndex < sheet.getLastRowNum()
                ? readRowValues(sheet, rowIndex + 1, width, merged, formatter, evaluator)
                : List.of();
        int nextFilled = (int) next.stream().filter(v -> v != null && !v.isBlank()).count();
        int nextNumeric = (int) next.stream().filter(ExcelHeaderRowDetector::looksNumeric).count();
        if (nextFilled >= filled - 1 && nextNumeric >= Math.max(1, nextFilled / 3)) {
            score += 35;
        }
        return score;
    }

    private static boolean looksNumeric(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.matches("^[A-Za-z]{1,3}\\d+$")) {
            return false;
        }
        return trimmed.matches("^[+-]?\\d([\\d,]*\\.?\\d*)?$")
                || trimmed.matches("^[+-]?\\d{4}([/-]\\d{1,2}){1,2}$");
    }
}
