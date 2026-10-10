package com.modocument.infrastructure.transform.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Turns the records of a text table (any strategy that splits lines into
 * cells) into a {@link Candidate}: modal width, header row detection,
 * preamble skipped, optional row repair, uniformity measured, then
 * {@link TableCleaner}.
 */
final class TextTables {

    /** Repairs a row that is wider than the header ({@code row, target width -> row}). */
    interface RowRepair extends BiFunction<List<String>, Integer, List<String>> {
    }

    private static final Pattern THOUSANDS_HEAD = Pattern.compile("[-+(]?[$€£]?\\d{1,3}");
    private static final Pattern THOUSANDS_TAIL_DECIMAL = Pattern.compile("\\d{3}\\.\\d+\\)?");
    private static final Pattern THOUSANDS_TAIL = Pattern.compile("\\d{3}(\\.\\d+)?\\)?");

    /**
     * Comma CSV rows split by an unquoted thousands separator
     * ({@code Ann,1,234.56,…}): adjacent {@code 1} + {@code 234.56} cells are
     * joined back, tails with decimals first, until the row fits the header.
     */
    static final RowRepair UNQUOTED_THOUSANDS = (row, target) -> {
        List<String> cells = new ArrayList<>(row);
        for (Pattern tail : List.of(THOUSANDS_TAIL_DECIMAL, THOUSANDS_TAIL)) {
            boolean merged = true;
            while (merged && trimmedWidth(cells) > target) {
                merged = false;
                for (int i = 0; i + 1 < cells.size(); i++) {
                    String head = TextCells.clean(cells.get(i));
                    String next = TextCells.clean(cells.get(i + 1));
                    if (THOUSANDS_HEAD.matcher(head).matches() && tail.matcher(next).matches()) {
                        cells.set(i, head + "," + next);
                        cells.remove(i + 1);
                        merged = true;
                        break;
                    }
                }
            }
        }
        return trimmedWidth(cells) <= target ? cells : row;
    };

    private TextTables() {
    }

    /** Most common record width among records with at least two non-blank cells (0 when none). */
    static int modalWidth(List<List<String>> records) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (List<String> record : records) {
            if (TextCells.nonBlankCount(record) >= 2) {
                counts.merge(trimmedWidth(record), 1, Integer::sum);
            }
        }
        int best = 0;
        int bestCount = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            int width = entry.getKey();
            int count = entry.getValue();
            if (count > bestCount || (count == bestCount && width > best)) {
                best = width;
                bestCount = count;
            }
        }
        return best;
    }

    /** Width without trailing blank cells (a trailing delimiter is not a column). */
    static int trimmedWidth(List<String> record) {
        int width = record.size();
        while (width > 0 && TextCells.isBlank(record.get(width - 1))) {
            width--;
        }
        return width;
    }

    static Optional<Candidate> build(CodeStrategy strategy, List<List<String>> records,
                                     ExtractionContext context, List<String> notes) {
        return build(strategy, records, context, notes, null);
    }

    static Optional<Candidate> build(CodeStrategy strategy, List<List<String>> records,
                                     ExtractionContext context, List<String> notes, RowRepair repair) {
        List<List<String>> nonBlank = new ArrayList<>(records.size());
        for (List<String> record : records) {
            if (TextCells.nonBlankCount(record) > 0) {
                nonBlank.add(record);
            }
        }
        int width = modalWidth(nonBlank);
        if (width < 2 || nonBlank.size() < 2) {
            return Optional.empty();
        }
        HeaderRowDetector.Pick pick = HeaderRowDetector.detect(nonBlank, width, context.matcher());
        List<String> headerCells = pick.header() ? nonBlank.get(pick.index()) : null;
        int firstData = pick.header() ? pick.index() + 1 : 0;
        if (!pick.header()) {
            while (firstData < nonBlank.size() && TextCells.nonBlankCount(nonBlank.get(firstData)) < 2) {
                firstData++;
            }
        }
        List<List<String>> data = new ArrayList<>(nonBlank.subList(firstData, nonBlank.size()));
        if (data.isEmpty()) {
            return Optional.empty();
        }

        if (headerCells != null && repair != null) {
            int named = trimmedWidth(headerCells);
            int repaired = 0;
            for (int r = 0; r < data.size(); r++) {
                List<String> row = data.get(r);
                if (trimmedWidth(row) > named) {
                    List<String> fixed = repair.apply(row, named);
                    if (fixed != row) {
                        data.set(r, fixed);
                        repaired++;
                    }
                }
            }
            if (repaired > 0) {
                notes.add("rejoined " + repaired + " row" + (repaired == 1 ? "" : "s")
                        + " split by an unquoted thousands separator");
                width = Math.max(named, modalWidth(data));
            }
        }

        List<String> headers;
        if (headerCells != null) {
            width = Math.max(width, trimmedWidth(headerCells));
            headers = TextCells.uniqueHeaders(TextCells.fit(headerCells, width));
            if (pick.index() > 0) {
                notes.add("header on line " + (pick.index() + 1) + ", " + pick.index()
                        + " leading line" + (pick.index() == 1 ? "" : "s") + " skipped");
            }
        } else {
            headers = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                headers.add("Column" + (i + 1));
            }
            notes.add("no header row found; columns are numbered");
        }

        int uniform = 0;
        int spilled = 0;
        for (List<String> row : data) {
            int rowWidth = trimmedWidth(row);
            if (rowWidth > width) {
                spilled++;
            } else if (rowWidth >= width - 1 || row.size() == width) {
                uniform++;
            }
        }
        if (spilled > 0) {
            notes.add(spilled + " row" + (spilled == 1 ? " had" : "s had")
                    + " more cells than the header; the extra cells were left out");
        }
        List<String> cleanNotes = new ArrayList<>(notes);
        TableCleaner.Table table = TableCleaner.clean(headers, data, cleanNotes);
        if (table.rows().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(strategy, table.headers(), table.rows(), false,
                (double) uniform / data.size(), cleanNotes));
    }
}
