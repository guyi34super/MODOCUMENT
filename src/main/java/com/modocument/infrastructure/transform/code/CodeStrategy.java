package com.modocument.infrastructure.transform.code;

/**
 * The code extraction strategies. The name is what the parse step records as
 * {@code ParseStrategy}; declaration order breaks score ties (the more
 * structured source wins).
 */
public enum CodeStrategy {
    /** Spreadsheet/CSV cells read by {@code TabularNormalizer}. */
    NATIVE_SHEET,
    /** Pipe tables ({@code | a | b |}). */
    MARKDOWN_TABLE,
    /** The workbook serialization {@code Row N: a<TAB>b}. */
    ROW_LINE,
    /** Delimited text (, ; tab |) with sniffing, quotes, BOM and encoding fallback. */
    DELIMITED,
    /** Columns aligned by whitespace, e.g. PDF text. */
    FIXED_WIDTH,
    /** Label-anchored values ({@code Label: value}), one record per repeated block. */
    KEY_VALUE,
    /** Each field's value regex, one record per match. */
    FIELD_REGEX
}
