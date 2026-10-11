package com.modocument.connector.core.db;

import java.util.regex.Pattern;

/**
 * The ONE identifier rule for table, schema and column names that reach SQL
 * text (the DATABASE connector on both sides, and the server's database
 * export and extraction through its {@code JdbcIdentifiers}): ASCII letters,
 * digits and underscore, not starting with a digit, at most 128 characters —
 * no dots, quotes, comments, spaces or unicode confusables. Names are then
 * quoted the vendor's way ({@link DbDialect#quote}); values only ever travel
 * as bound parameters.
 */
public final class DbIdentifiers {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private DbIdentifiers() {
    }

    /** Returns {@code value}, or throws an IllegalArgumentException naming {@code what}. */
    public static String require(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + what + " '" + value + "': only letters, digits, and underscores "
                            + "are allowed (max 128 chars, must not start with a digit).");
        }
        return value;
    }
}
