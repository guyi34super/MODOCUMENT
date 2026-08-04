package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the smallest LLM payload scope from the user's instruction and
 * optional UI hints (validation stepper row/column). No extra LLM call.
 */
public final class ChatEditScopeResolver {

    private static final Pattern BULK =
            Pattern.compile("\\b(all|every|required|empty\\s+fields?|fix\\s+all)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW_NUMBER =
            Pattern.compile("\\brow\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_ROW =
            Pattern.compile("\\bthis\\s+row\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_PHRASE =
            Pattern.compile("\\b(\\w+)\\s+(column|columns|field|fields)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VAGUE_FIX =
            Pattern.compile("\\b(fix|correct|update|change|repair)\\b(\\s+(it|this|that|the\\s+value))?$",
                    Pattern.CASE_INSENSITIVE);

    private ChatEditScopeResolver() {
    }

    /** True when the instruction reads as a deliberate bulk change ("all", "every", ...). */
    public static boolean isBulkInstruction(String instruction) {
        return instruction != null && BULK.matcher(instruction).find();
    }

    public static ChatEditScopeResolution resolve(String instruction,
                                                  Integer hintRow,
                                                  String hintColumn,
                                                  List<String> tableColumns,
                                                  List<RowValidationResult> validation) {
        String text = instruction == null ? "" : instruction.trim();
        List<String> columns = tableColumns == null ? List.of() : tableColumns;

        if (BULK.matcher(text).find()) {
            return new ChatEditScopeResolution(ChatEditScope.ALL_ERRORS, null, null, List.of());
        }

        List<String> matchedColumns = matchColumns(text, columns);
        if (!matchedColumns.isEmpty()) {
            return new ChatEditScopeResolution(
                    ChatEditScope.COLUMN, null, matchedColumns.get(0), matchedColumns);
        }

        Integer rowFromInstruction = parseRowNumber(text);
        if (rowFromInstruction != null) {
            return new ChatEditScopeResolution(ChatEditScope.ROW, rowFromInstruction, null, List.of());
        }
        if (THIS_ROW.matcher(text).find() && hintRow != null) {
            return new ChatEditScopeResolution(ChatEditScope.ROW, hintRow, null, List.of());
        }

        if (isVagueFix(text)) {
            if (hintRow != null && hintColumn != null && !hintColumn.isBlank()) {
                return new ChatEditScopeResolution(ChatEditScope.CELL, hintRow, hintColumn, List.of());
            }
            if (hintRow != null) {
                return new ChatEditScopeResolution(ChatEditScope.ROW, hintRow, null, List.of());
            }
        }

        if (hintRow != null && hintColumn != null && !hintColumn.isBlank()
                && mentionsColumn(text, hintColumn)) {
            return new ChatEditScopeResolution(ChatEditScope.COLUMN, null, hintColumn, List.of(hintColumn));
        }

        if (hintRow != null && !hasExplicitRowSignal(text)) {
            return new ChatEditScopeResolution(ChatEditScope.ROW, hintRow, null, List.of());
        }

        return new ChatEditScopeResolution(ChatEditScope.ALL_ERRORS, null, null, List.of());
    }

    static List<String> matchColumns(String instruction, List<String> tableColumns) {
        if (instruction.isBlank() || tableColumns.isEmpty()) {
            return List.of();
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        List<String> exact = new ArrayList<>();
        for (String column : tableColumns) {
            if (containsWord(lower, column.toLowerCase(Locale.ROOT))) {
                exact.add(column);
            }
        }
        if (!exact.isEmpty()) {
            exact.sort(Comparator.comparingInt(tableColumns::indexOf));
            return exact;
        }

        Matcher phrase = COLUMN_PHRASE.matcher(instruction);
        if (phrase.find()) {
            String stem = phrase.group(1).toLowerCase(Locale.ROOT);
            List<String> fuzzy = tableColumns.stream()
                    .filter(col -> col.toLowerCase(Locale.ROOT).contains(stem))
                    .toList();
            if (!fuzzy.isEmpty()) {
                return fuzzy;
            }
        }
        return List.of();
    }

    private static boolean mentionsColumn(String instruction, String column) {
        return containsWord(instruction.toLowerCase(Locale.ROOT),
                column.toLowerCase(Locale.ROOT));
    }

    private static boolean containsWord(String haystack, String needle) {
        if (needle.isBlank()) {
            return false;
        }
        Pattern p = Pattern.compile("\\b" + Pattern.quote(needle) + "\\b", Pattern.CASE_INSENSITIVE);
        return p.matcher(haystack).find();
    }

    private static Integer parseRowNumber(String instruction) {
        Matcher m = ROW_NUMBER.matcher(instruction);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isVagueFix(String instruction) {
        return VAGUE_FIX.matcher(instruction.trim()).find();
    }

    private static boolean hasExplicitRowSignal(String instruction) {
        return ROW_NUMBER.matcher(instruction).find() || THIS_ROW.matcher(instruction).find();
    }
}
