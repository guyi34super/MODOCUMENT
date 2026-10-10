package com.modocument.infrastructure.transform.code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Columns aligned with spaces — report-style text and PDF text (for PDFs the
 * position-preserving {@link PdfLayoutText}). A block is a run of at least
 * three lines with a multi-space gap; column boundaries are the character
 * columns that are blank in (almost) every line of the block, so ragged right
 * edges and right-aligned numbers both work. A one-character gap counts only
 * where most lines are separated by two or more spaces there (so single spaces
 * inside cells never split them). Wrapped cells (a line with only one text
 * cell and no first cell) are joined to the row above, and blocks that repeat
 * the header after a page break are merged.
 */
final class FixedWidthStrategy implements ExtractionStrategy {

    static final int MIN_BLOCK_LINES = 3;
    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_LINE = 2_000;
    private static final int CUT_SEARCH = 3;
    private static final int MAX_OUTLIER_LINES = 2;
    private static final int EDGE_LINES_TOP = 4;
    private static final int EDGE_LINES_BOTTOM = 2;
    private static final Pattern MULTI_GAP = Pattern.compile("\\S {2,}\\S");

    @Override
    public CodeStrategy name() {
        return CodeStrategy.FIXED_WIDTH;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        String layout = context.pdfLayoutText();
        String text = layout != null ? layout : context.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        // Not \\R, which splits at form feeds too; a form-feed line is a page break here.
        for (String line : text.split("\\r?\\n|\\r", -1)) {
            lines.add(expandTabs(line));
        }
        List<Parsed> parsed = new ArrayList<>();
        for (List<Integer> block : blocks(lines)) {
            Parsed table = parseBlock(block, lines, context);
            if (table != null) {
                parsed.add(table);
            }
        }
        List<Parsed> merged = mergePages(parsed);
        merged.sort(Comparator.comparingInt((Parsed p) -> p.rows.size()).reversed());
        List<Candidate> candidates = new ArrayList<>();
        for (Parsed table : merged.subList(0, Math.min(MAX_CANDIDATES, merged.size()))) {
            List<String> notes = new ArrayList<>(table.notes);
            if (layout != null) {
                notes.add(0, "PDF text re-laid out by position");
            }
            TableCleaner.Table clean = TableCleaner.clean(table.headers, table.rows, notes);
            if (!clean.rows().isEmpty()) {
                candidates.add(new Candidate(CodeStrategy.FIXED_WIDTH, clean.headers(), clean.rows(),
                        false, table.uniformity(), notes));
            }
        }
        return candidates;
    }

    /** A parsed block: headers (real or numbered), rows and how many lines needed a forced cut. */
    private static final class Parsed {
        List<String> headers;
        boolean realHeader;
        List<List<String>> rows = new ArrayList<>();
        int lines;
        int forced;
        List<String> notes = new ArrayList<>();

        double uniformity() {
            return lines == 0 ? 0 : 1.0 - (double) forced / lines;
        }
    }

    /** Line indexes of each block: multi-gap lines, allowing one blank line and two loose lines inside. */
    static List<List<Integer>> blocks(List<String> lines) {
        List<List<Integer>> blocks = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int strong = 0;
        int blankRun = 0;
        int looseRun = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean pageBreak = line.indexOf('\f') >= 0;
            if (pageBreak || line.isBlank()) {
                blankRun++;
                if (pageBreak || blankRun > 1) {
                    strong = close(blocks, current, strong, lines);
                    current = new ArrayList<>();
                }
                continue;
            }
            blankRun = 0;
            if (line.length() <= MAX_LINE && MULTI_GAP.matcher(line).find()) {
                current.add(i);
                strong++;
                looseRun = 0;
            } else if (!current.isEmpty() && looseRun < 2) {
                current.add(i);
                looseRun++;
            } else {
                strong = close(blocks, current, strong, lines);
                current = new ArrayList<>();
                looseRun = 0;
            }
        }
        close(blocks, current, strong, lines);
        return blocks;
    }

    private static int close(List<List<Integer>> blocks, List<Integer> current, int strong, List<String> lines) {
        // Trailing loose lines are footers, not wrapped cells.
        while (!current.isEmpty() && !MULTI_GAP.matcher(lines.get(current.get(current.size() - 1))).find()) {
            current.remove(current.size() - 1);
        }
        if (strong >= MIN_BLOCK_LINES && !current.isEmpty()) {
            blocks.add(new ArrayList<>(current));
        }
        return 0;
    }

    private static Parsed parseBlock(List<Integer> block, List<String> lines, ExtractionContext context) {
        List<String> strongLines = new ArrayList<>();
        for (int index : block) {
            String line = lines.get(index);
            if (MULTI_GAP.matcher(line).find()) {
                strongLines.add(line);
            }
        }
        List<int[]> gaps = gaps(strongLines);
        if (gaps.isEmpty()) {
            return null;
        }
        List<List<String>> records = new ArrayList<>();
        List<Boolean> forcedCuts = new ArrayList<>();
        for (int index : block) {
            boolean[] forced = new boolean[1];
            records.add(cut(lines.get(index), gaps, forced));
            forcedCuts.add(forced[0]);
        }
        int width = gaps.size() + 1;
        HeaderRowDetector.Pick pick = HeaderRowDetector.detect(records, width, context.matcher());
        Parsed parsed = new Parsed();
        int first;
        if (pick.header()) {
            parsed.headers = TextCells.uniqueHeaders(records.get(pick.index()));
            parsed.realHeader = true;
            first = pick.index() + 1;
            if (pick.index() > 0) {
                parsed.notes.add("header on block line " + (pick.index() + 1));
            }
        } else {
            parsed.headers = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                parsed.headers.add("Column" + (i + 1));
            }
            first = 0;
        }
        parsed.notes.add(width + " columns from whitespace gaps over " + block.size() + " lines");
        int joined = 0;
        for (int r = first; r < records.size(); r++) {
            List<String> row = records.get(r);
            parsed.lines++;
            if (forcedCuts.get(r)) {
                parsed.forced++;
            }
            if (!parsed.rows.isEmpty() && isContinuation(row)) {
                List<String> previous = parsed.rows.get(parsed.rows.size() - 1);
                for (int c = 0; c < row.size(); c++) {
                    if (!row.get(c).isEmpty()) {
                        String above = previous.get(c);
                        previous.set(c, above.isEmpty() ? row.get(c) : above + " " + row.get(c));
                    }
                }
                joined++;
                continue;
            }
            parsed.rows.add(new ArrayList<>(row));
        }
        if (joined > 0) {
            parsed.notes.add("joined " + joined + " wrapped line" + (joined == 1 ? "" : "s"));
        }
        return parsed.rows.isEmpty() ? null : parsed;
    }

    /** A wrapped cell: no first cell and a single non-numeric text cell. */
    private static boolean isContinuation(List<String> row) {
        if (row.isEmpty() || !row.get(0).isEmpty() || TextCells.nonBlankCount(row) != 1) {
            return false;
        }
        for (String cell : row) {
            if (!cell.isEmpty()) {
                return !TextCells.looksNumeric(cell) && !TextCells.looksDate(cell);
            }
        }
        return false;
    }

    /**
     * Inclusive [start, end] column ranges that separate columns: blank in
     * every line. A stray line at the edge of a block (an "Account …  Period
     * …" preamble above the header, a note below the table) can hide a
     * separator, so up to two edge lines are set aside when doing so reveals
     * more separators at least two columns wide (a one-column "separator"
     * revealed that way is usually two cells' words lining up by chance);
     * those lines are still cut, just not trusted for the layout. Lines
     * inside the block are never set aside.
     */
    static List<int[]> gaps(List<String> lines) {
        List<String> core = new ArrayList<>(lines);
        List<int[]> best = gaps(core, 0);
        for (int round = 0; round < MAX_OUTLIER_LINES && core.size() > MIN_BLOCK_LINES; round++) {
            int drop = -1;
            List<int[]> improved = best;
            for (int index : edgeIndexes(core.size())) {
                List<String> without = new ArrayList<>(core);
                without.remove(index);
                List<int[]> candidate = gaps(without, 0);
                if (wide(candidate) > wide(improved)) {
                    improved = candidate;
                    drop = index;
                }
            }
            if (drop < 0) {
                break;
            }
            core.remove(drop);
            best = improved;
        }
        return best;
    }

    private static int wide(List<int[]> gaps) {
        int count = 0;
        for (int[] gap : gaps) {
            if (gap[1] - gap[0] >= 1) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> edgeIndexes(int size) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < Math.min(EDGE_LINES_TOP, size); i++) {
            indexes.add(i);
        }
        for (int i = Math.max(EDGE_LINES_TOP, size - EDGE_LINES_BOTTOM); i < size; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    private static List<int[]> gaps(List<String> lines, int allowance) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        int[] occupied = new int[width];
        int leftmost = width;
        for (String line : lines) {
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) != ' ') {
                    occupied[c]++;
                    leftmost = Math.min(leftmost, c);
                }
            }
        }
        List<int[]> gaps = new ArrayList<>();
        int c = leftmost;
        while (c < width && occupied[c] <= allowance) {
            c++;
        }
        while (c < width) {
            if (occupied[c] > allowance) {
                c++;
                continue;
            }
            int start = c;
            while (c < width && occupied[c] <= allowance) {
                c++;
            }
            if (c >= width) {
                break; // trailing blank area, not a separator
            }
            int end = c - 1;
            if (end - start + 1 >= 2 || mostlyWide(lines, start)) {
                gaps.add(new int[]{start, end});
            }
        }
        return gaps;
    }

    /** True when most lines have a whitespace run of two or more (or have ended) at {@code column}. */
    private static boolean mostlyWide(List<String> lines, int column) {
        int wide = 0;
        for (String line : lines) {
            if (column >= line.length()) {
                wide++;
                continue;
            }
            int left = column;
            while (left > 0 && line.charAt(left - 1) == ' ') {
                left--;
            }
            int right = column;
            while (right < line.length() && line.charAt(right) == ' ') {
                right++;
            }
            if (right - left >= 2 || right >= line.length()) {
                wide++;
            }
        }
        return wide * 2 > lines.size();
    }

    /** Cells of one line: cut inside each gap where the line has a space, else at the nearest space. */
    static List<String> cut(String line, List<int[]> gaps, boolean[] forced) {
        List<String> cells = new ArrayList<>(gaps.size() + 1);
        int from = 0;
        for (int[] gap : gaps) {
            int at = -1;
            for (int c = gap[0]; c <= gap[1]; c++) {
                if (c >= line.length() || line.charAt(c) == ' ') {
                    at = c;
                    break;
                }
            }
            if (at < 0) {
                for (int d = 1; d <= CUT_SEARCH && at < 0; d++) {
                    if (gap[0] - d > from && gap[0] - d < line.length() && line.charAt(gap[0] - d) == ' ') {
                        at = gap[0] - d;
                    } else if (gap[1] + d < line.length() && line.charAt(gap[1] + d) == ' ') {
                        at = gap[1] + d;
                    }
                }
                if (at < 0) {
                    at = gap[0];
                }
                forced[0] = true;
            }
            at = Math.max(at, from);
            cells.add(TextCells.clean(substring(line, from, at)));
            from = at;
        }
        cells.add(TextCells.clean(substring(line, from, line.length())));
        return cells;
    }

    private static List<Parsed> mergePages(List<Parsed> parsed) {
        List<Parsed> merged = new ArrayList<>();
        for (Parsed table : parsed) {
            Parsed last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            boolean sameHeader = last != null && table.realHeader && last.realHeader
                    && keys(table.headers).equals(keys(last.headers));
            boolean headerless = last != null && !table.realHeader && last.realHeader
                    && table.headers.size() == last.headers.size();
            if (sameHeader || headerless) {
                last.rows.addAll(table.rows);
                last.lines += table.lines;
                last.forced += table.forced;
                if (!last.notes.contains("merged blocks across page breaks")) {
                    last.notes.add("merged blocks across page breaks");
                }
                continue;
            }
            merged.add(table);
        }
        return merged;
    }

    private static List<String> keys(List<String> headers) {
        return headers.stream().map(TextCells::key).toList();
    }

    private static String substring(String line, int from, int to) {
        if (from >= line.length()) {
            return "";
        }
        return line.substring(from, Math.min(to, line.length()));
    }

    private static String expandTabs(String line) {
        if (line.indexOf('\t') < 0) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\t') {
                do {
                    out.append(' ');
                } while (out.length() % 4 != 0);
                out.append(' ');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }
}
