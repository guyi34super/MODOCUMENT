package com.modocument.infrastructure.export;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.Driver;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
            "jdbc:postgresql:", "org.postgresql.Driver",
            "jdbc:h2:", "org.h2.Driver");

    /**
     * Connection options a caller may set. Everything else is rejected —
     * embedded databases treat several options as code-execution hooks
     * (H2's {@code INIT=RUNSCRIPT FROM …} runs arbitrary SQL at connect
     * time, and tracing options write arbitrary files), so an allow-list is
     * the only safe way to accept a user-supplied URL.
     */
    private static final Set<String> ALLOWED_URL_OPTIONS = Set.of(
            "DB_CLOSE_DELAY", "DB_CLOSE_ON_EXIT", "MODE", "IFEXISTS",
            "AUTO_SERVER", "AUTO_RECONNECT", "DEFAULT_LOCK_TIMEOUT",
            "CASE_INSENSITIVE_IDENTIFIERS", "NON_KEYWORDS");

    /**
     * Query parameters a caller may set on a PostgreSQL URL. The driver reads
     * several of them as class names or file paths ({@code socketFactory},
     * {@code sslfactory}, {@code loggerFile}), which turns a connection string
     * into a code-loading and file-writing primitive — so this is an
     * allow-list of the connection tuning users actually need.
     */
    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of(
            "SSL", "SSLMODE", "CURRENTSCHEMA", "APPLICATIONNAME", "CONNECTTIMEOUT",
            "LOGINTIMEOUT", "SOCKETTIMEOUT", "TCPKEEPALIVE", "READONLY", "PREPARETHRESHOLD");

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
        String url = requireSafeUrl(jdbcUrl);
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

    /**
     * Rejects connection URLs carrying options that would let a caller run
     * SQL or touch the filesystem on connect.
     */
    static String requireSafeUrl(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.trim();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20) {
                throw new IllegalArgumentException(
                        "jdbcUrl must not contain whitespace or control characters.");
            }
        }
        int question = url.indexOf('?');
        String semicolonPart = question < 0 ? url : url.substring(0, question);
        String queryPart = question < 0 ? "" : url.substring(question + 1);

        requireAllowedOptions(semicolonPart.split(";"), 1, ALLOWED_URL_OPTIONS, "option");
        if (!queryPart.isEmpty()) {
            requireAllowedOptions(queryPart.split("&"), 0, ALLOWED_QUERY_PARAMS, "parameter");
        }
        return url;
    }

    private static void requireAllowedOptions(String[] parts, int from, Set<String> allowed,
                                              String what) {
        for (int i = from; i < parts.length; i++) {
            String option = parts[i];
            if (option.isBlank()) {
                continue;
            }
            int equals = option.indexOf('=');
            String key = (equals < 0 ? option : option.substring(0, equals))
                    .trim().toUpperCase(Locale.ROOT);
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("jdbcUrl " + what + " '" + key
                        + "' is not allowed; permitted " + what + "s: "
                        + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
            }
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
