package com.modocument.infrastructure.transform.code;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Delimited text via Commons CSV: the delimiter is sniffed among
 * {@code , ; TAB |} (the one giving the most consistent record width wins
 * the sniff; every plausible one becomes a candidate so the scorer has the
 * last word), quoted fields may hold delimiters and newlines, the raw file
 * is re-decoded (BOM, windows-1252) for delimited types, the header row is
 * detected below any title lines, and totals/footers are dropped.
 */
final class DelimitedStrategy implements ExtractionStrategy {

    static final char[] DELIMITERS = {',', ';', '\t', '|'};
    private static final int SNIFF_CHARS = 128 * 1024;
    private static final int SNIFF_RECORDS = 200;
    private static final Pattern ROW_LINE = Pattern.compile("(?m)^\\s*Row\\s+\\d+:");

    @Override
    public CodeStrategy name() {
        return CodeStrategy.DELIMITED;
    }

    @Override
    public List<Candidate> propose(ExtractionContext context) {
        String text = context.delimitedText();
        if (text == null || text.isBlank() || isRowLineText(text)) {
            return List.of();
        }
        String sample = text.length() > SNIFF_CHARS ? text.substring(0, SNIFF_CHARS) : text;
        List<Candidate> candidates = new ArrayList<>();
        for (char delimiter : DELIMITERS) {
            if (sample.indexOf(delimiter) < 0) {
                continue;
            }
            double consistency = sniff(sample, delimiter);
            if (consistency < 0.5) {
                continue;
            }
            List<List<String>> records = parse(text, delimiter, Integer.MAX_VALUE);
            if (records.isEmpty()) {
                continue;
            }
            List<String> notes = new ArrayList<>();
            notes.add("delimiter " + describe(delimiter));
            String decoded = context.delimitedNote();
            if (decoded != null) {
                notes.add(decoded);
            }
            TextTables.RowRepair repair = delimiter == ',' ? TextTables.UNQUOTED_THOUSANDS : null;
            TextTables.build(CodeStrategy.DELIMITED, records, context, notes, repair)
                    .ifPresent(candidates::add);
        }
        return candidates;
    }

    /**
     * Share of non-blank sample records that have the modal width (at least
     * two cells, at least two such records), or 0 when the delimiter does
     * not split the sample into a table.
     */
    static double sniff(String sample, char delimiter) {
        List<List<String>> records = parse(sample, delimiter, SNIFF_RECORDS);
        int nonBlank = 0;
        for (List<String> record : records) {
            if (TextCells.nonBlankCount(record) > 0) {
                nonBlank++;
            }
        }
        int width = TextTables.modalWidth(records);
        if (width < 2 || nonBlank < 2) {
            return 0;
        }
        int atWidth = 0;
        for (List<String> record : records) {
            if (TextCells.nonBlankCount(record) > 0 && TextTables.trimmedWidth(record) == width) {
                atWidth++;
            }
        }
        return atWidth < 2 ? 0 : (double) atWidth / nonBlank;
    }

    static List<List<String>> parse(String text, char delimiter, int maxRecords) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .setLenientEof(true)
                .setTrailingData(true)
                .build();
        List<List<String>> records = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                records.add(record.toList());
                if (records.size() >= maxRecords) {
                    break;
                }
            }
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            // Malformed quoting for this delimiter: keep what parsed cleanly.
        }
        return records;
    }

    private static boolean isRowLineText(String text) {
        long rowLines = ROW_LINE.matcher(text).results().limit(1_000).count();
        long lines = text.lines().filter(l -> !l.isBlank()).limit(2_000).count();
        return rowLines >= 2 && rowLines * 2 >= Math.min(lines, 1_000);
    }

    private static String describe(char delimiter) {
        return switch (delimiter) {
            case '\t' -> "TAB";
            case ',' -> "comma";
            case ';' -> "semicolon";
            case '|' -> "pipe";
            default -> String.valueOf(delimiter);
        };
    }
}
