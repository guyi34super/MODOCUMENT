package com.modocument.infrastructure.transform.code;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the real header among leading title/blank rows of a text table,
 * following {@code ExcelHeaderRowDetector}'s scoring (filled, text-like,
 * short cells; numeric data below) plus a bonus for cells that name a
 * template field.
 */
final class HeaderRowDetector {

    static final int SCAN_LIMIT = 15;

    /** The chosen row and whether it really looks like a header (otherwise columns are generated). */
    record Pick(int index, boolean header) {
    }

    private HeaderRowDetector() {
    }

    static Pick detect(List<List<String>> rows, int width, FieldMatcher matcher) {
        int limit = Math.min(rows.size(), SCAN_LIMIT);
        int bestRow = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int r = 0; r < limit; r++) {
            List<String> next = r + 1 < rows.size() ? rows.get(r + 1) : List.of();
            int score = score(rows.get(r), next, width, matcher);
            if (score > bestScore) {
                bestScore = score;
                bestRow = r;
            }
        }
        if (rows.isEmpty()) {
            return new Pick(0, false);
        }
        return new Pick(bestRow, isHeaderLike(rows.get(bestRow), matcher));
    }

    /** Mostly text, short, distinct cells — or at least two cells naming fields. */
    static boolean isHeaderLike(List<String> cells, FieldMatcher matcher) {
        int filled = 0;
        int numeric = 0;
        int fieldHits = 0;
        for (String cell : cells) {
            if (TextCells.isBlank(cell)) {
                continue;
            }
            filled++;
            if (TextCells.looksNumeric(cell) || TextCells.looksDate(cell)) {
                numeric++;
            }
            if (matcher.bestScore(cell) >= FieldMatcher.HEADER_THRESHOLD) {
                fieldHits++;
            }
        }
        if (fieldHits >= 2 || (fieldHits >= 1 && filled == 1)) {
            return true;
        }
        return filled >= 2 && numeric == 0;
    }

    static int score(List<String> cells, List<String> next, int width, FieldMatcher matcher) {
        int filled = 0;
        int textLike = 0;
        int numericLike = 0;
        int totalLen = 0;
        int fieldHits = 0;
        Set<String> distinct = new HashSet<>();
        for (String raw : cells) {
            String cell = TextCells.clean(raw);
            if (cell.isEmpty()) {
                continue;
            }
            filled++;
            distinct.add(cell.toLowerCase(Locale.ROOT));
            totalLen += cell.length();
            if (TextCells.looksNumeric(cell) || TextCells.looksDate(cell)) {
                numericLike++;
            } else {
                textLike++;
            }
            if (matcher.bestScore(cell) >= FieldMatcher.HEADER_THRESHOLD) {
                fieldHits++;
            }
        }
        if (filled < 2 && fieldHits == 0) {
            return -1_000;
        }
        if (distinct.size() == 1 && filled >= 2) {
            return -800;
        }
        int avgLen = totalLen / Math.max(1, filled);
        if (avgLen > 48) {
            return -600;
        }
        int score = filled * 12 + textLike * 4 - numericLike * 8 + fieldHits * 30;
        if (avgLen <= 24) {
            score += 20;
        }
        if (filled >= 3) {
            score += 15;
        }
        if (filled < Math.max(2, width / 2)) {
            score -= 25;
        }
        int nextFilled = TextCells.nonBlankCount(next);
        int nextNumeric = 0;
        for (String cell : next) {
            if (TextCells.looksNumeric(cell) || TextCells.looksDate(cell)) {
                nextNumeric++;
            }
        }
        if (nextFilled >= filled - 1 && nextNumeric >= Math.max(1, nextFilled / 3)) {
            score += 35;
        }
        return score;
    }
}
