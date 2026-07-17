package com.modocument.infrastructure.export;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.Driver;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL-injection-safe JDBC helpers shared by database export and database
 * extraction: identifiers are validated against a strict allow-list AND
 * double-quoted, values only ever travel as bound parameters, and only
 * drivers actually on the classpath are accepted.
 */
public final class JdbcIdentifiers {

    /** Conservative identifier rule for table/column names. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    /** Drivers actually on the classpath; other targets need a driver dependency first. */
    private static final Map<String, String> DRIVERS_BY_URL_PREFIX = Map.of(
            "jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "jdbc:h2:", "org.h2.Driver");

    private JdbcIdentifiers() {
    }

    public static String requireValidIdentifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + what + " '" + value + "': only letters, digits, and underscores "
                            + "are allowed (max 128 chars, must not start with a digit).");
        }
        return value;
    }

    public static String quote(String identifier) {
        return '"' + identifier + '"';
    }

    /** Builds a one-off data source; credentials are never stored or logged. */
    public static SimpleDriverDataSource dataSource(String jdbcUrl, String username, String password) {
        String url = jdbcUrl == null ? "" : jdbcUrl.trim();
        String driverClass = DRIVERS_BY_URL_PREFIX.entrySet().stream()
                .filter(e -> url.toLowerCase(Locale.ROOT).startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported jdbcUrl; supported prefixes: "
                                + String.join(", ", DRIVERS_BY_URL_PREFIX.keySet())));
        try {
            Driver driver = (Driver) Class.forName(driverClass)
                    .getDeclaredConstructor().newInstance();
            return new SimpleDriverDataSource(driver, url, username, password);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("JDBC driver " + driverClass + " is unavailable.", e);
        }
    }

    /** Host portion of the JDBC url, safe to persist and log (no credentials). */
    public static String describeTarget(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        int semicolon = jdbcUrl.indexOf(';');
        String base = semicolon < 0 ? jdbcUrl : jdbcUrl.substring(0, semicolon);
        int question = base.indexOf('?');
        return question < 0 ? base : base.substring(0, question);
    }
}
