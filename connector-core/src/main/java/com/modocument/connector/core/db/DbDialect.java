package com.modocument.connector.core.db;

import com.modocument.connector.core.support.ConnectorLimits;
import com.modocument.connector.core.support.Specs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Per-vendor SQL and JDBC URL generation for the DATABASE connector (ws25;
 * server and agent) and the server's database export. The constants are the
 * supported vendors ({@code vendor} config values). The URL is BUILT from
 * the connection's structured config — never accepted raw — and only
 * allow-listed options with allow-listed values reach it: driver options
 * that load classes, read local files, or run code at connect time can never
 * be set. Identifiers are validated with the {@link DbIdentifiers} rule
 * (ASCII letters, digits, underscore; no dots, quotes, comments, unicode
 * confusables) and quoted the vendor's way; values only ever travel as bound
 * parameters.
 */
public enum DbDialect {

    POSTGRESQL("org.postgresql.Driver", 5432, '"', '"') {
        @Override
        Map<String, Set<String>> allowedOptions() {
            return Map.of("sslmode", Set.of("disable", "allow", "prefer", "require", "verify-ca",
                    "verify-full"));
        }

        @Override
        public String url(String host, int port, String database, Map<String, String> options,
                          ConnectorLimits limits) {
            StringBuilder url = new StringBuilder("jdbc:postgresql://").append(host).append(':')
                    .append(port).append('/').append(database);
            Map<String, String> params = new LinkedHashMap<>(options);
            // Strings bind as "unspecified" so PostgreSQL casts them to the
            // column type (records arrive as text); timeouts are ours.
            params.put("stringtype", "unspecified");
            params.put("connectTimeout", String.valueOf(limits.connectTimeoutSeconds()));
            params.put("loginTimeout", String.valueOf(limits.connectTimeoutSeconds()));
            params.put("socketTimeout", String.valueOf(limits.readTimeoutSeconds() + 5));
            params.put("ApplicationName", "MoDocument-Integration");
            appendQuery(url, params, '?', '&');
            return url.toString();
        }

        @Override
        public String upsert(String table, List<String> columns, List<String> keys) {
            List<String> updates = nonKey(columns, keys);
            String conflict = " ON CONFLICT (" + quoteAll(keys) + ") DO "
                    + (updates.isEmpty() ? "NOTHING" : "UPDATE SET " + updates.stream()
                    .map(c -> quote(c) + " = EXCLUDED." + quote(c))
                    .collect(Collectors.joining(", ")));
            return insert(table, columns) + conflict;
        }
    },

    ORACLE("oracle.jdbc.OracleDriver", 1521, '"', '"') {
        @Override
        Map<String, Set<String>> allowedOptions() {
            return Map.of("protocol", Set.of("tcp", "tcps"));
        }

        @Override
        public String url(String host, int port, String database, Map<String, String> options,
                          ConnectorLimits limits) {
            String protocol = options.getOrDefault("protocol", "tcp").toLowerCase(Locale.ROOT);
            // EZConnect: database = the service name.
            return "jdbc:oracle:thin:@" + protocol + "://" + host + ":" + port + "/" + database;
        }

        @Override
        public void driverProperties(Properties properties, ConnectorLimits limits) {
            properties.setProperty("oracle.net.CONNECT_TIMEOUT",
                    String.valueOf(limits.connectTimeout().toMillis()));
            properties.setProperty("oracle.jdbc.ReadTimeout",
                    String.valueOf(limits.readTimeout().toMillis() + 5000));
            properties.setProperty("v$session.program", "MoDocument-Integration");
        }

        @Override
        public String upsert(String table, List<String> columns, List<String> keys) {
            String source = columns.stream().map(c -> "? AS " + quote(c))
                    .collect(Collectors.joining(", "));
            return "MERGE INTO " + table + " t USING (SELECT " + source + " FROM dual) s ON ("
                    + on(keys, "t", "s") + ")" + mergeTail(columns, keys, "t", "s");
        }
    },

    SQLSERVER("com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, '[', ']') {
        @Override
        Map<String, Set<String>> allowedOptions() {
            return Map.of(
                    "encrypt", Set.of("true", "false", "strict", "mandatory", "optional"),
                    "trustServerCertificate", Set.of("true", "false"),
                    "hostNameInCertificate", Set.of());
        }

        @Override
        public String url(String host, int port, String database, Map<String, String> options,
                          ConnectorLimits limits) {
            StringBuilder url = new StringBuilder("jdbc:sqlserver://").append(host).append(':')
                    .append(port);
            Map<String, String> params = new LinkedHashMap<>();
            params.put("databaseName", database);
            params.put("encrypt", "true");
            params.put("trustServerCertificate", "false");
            params.putAll(options);
            params.put("loginTimeout", String.valueOf(limits.connectTimeoutSeconds()));
            params.put("socketTimeout", String.valueOf(limits.readTimeout().toMillis() + 5000));
            params.put("applicationName", "MoDocument-Integration");
            appendQuery(url, params, ';', ';');
            return url.toString();
        }

        @Override
        public String upsert(String table, List<String> columns, List<String> keys) {
            String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
            return "MERGE INTO " + table + " WITH (HOLDLOCK) AS t USING (VALUES (" + placeholders
                    + ")) AS s (" + quoteAll(columns) + ") ON (" + on(keys, "t", "s") + ")"
                    + mergeTail(columns, keys, "t", "s") + ";";
        }
    },

    MARIADB("org.mariadb.jdbc.Driver", 3306, '`', '`') {
        @Override
        Map<String, Set<String>> allowedOptions() {
            return Map.of("sslMode", Set.of("disable", "trust", "verify-ca", "verify-full"));
        }

        @Override
        public String url(String host, int port, String database, Map<String, String> options,
                          ConnectorLimits limits) {
            StringBuilder url = new StringBuilder("jdbc:mariadb://").append(host).append(':')
                    .append(port).append('/').append(database);
            Map<String, String> params = new LinkedHashMap<>(options);
            // Never read client-side files on a server's request.
            params.put("allowLocalInfile", "false");
            params.put("connectTimeout", String.valueOf(limits.connectTimeout().toMillis()));
            params.put("socketTimeout", String.valueOf(limits.readTimeout().toMillis() + 5000));
            appendQuery(url, params, '?', '&');
            return url.toString();
        }

        @Override
        public String upsert(String table, List<String> columns, List<String> keys) {
            List<String> updates = nonKey(columns, keys);
            List<String> assignments = updates.isEmpty()
                    // No non-key column: a no-op assignment keeps the row.
                    ? List.of(quote(keys.get(0)) + " = " + quote(keys.get(0)))
                    : updates.stream().map(c -> quote(c) + " = VALUES(" + quote(c) + ")").toList();
            return insert(table, columns) + " ON DUPLICATE KEY UPDATE "
                    + String.join(", ", assignments);
        }
    };

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$");
    private static final Pattern DATABASE = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.$-]{0,127}$");
    private static final Pattern HOSTNAME_VALUE = Pattern.compile("^[A-Za-z0-9*.-]{1,253}$");

    private final String driverClass;
    private final int defaultPort;
    private final char open;
    private final char close;

    DbDialect(String driverClass, int defaultPort, char open, char close) {
        this.driverClass = driverClass;
        this.defaultPort = defaultPort;
        this.open = open;
        this.close = close;
    }

    public String driverClass() {
        return driverClass;
    }

    public int defaultPort() {
        return defaultPort;
    }

    abstract Map<String, Set<String>> allowedOptions();

    /** The JDBC URL for validated parts; options must have passed {@link #options}. */
    public abstract String url(String host, int port, String database, Map<String, String> options,
                               ConnectorLimits limits);

    /** Vendor timeouts that travel as driver properties rather than URL options. */
    public void driverProperties(Properties properties, ConnectorLimits limits) {
    }

    /** The vendor UPSERT for {@code table} (already qualified + quoted). */
    public abstract String upsert(String table, List<String> columns, List<String> keys);

    // ---- validation ---------------------------------------------------------

    public static String requireHost(String host) {
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("'host' must be a host name or IPv4 address.");
        }
        return host;
    }

    public static String requireDatabase(String database) {
        if (database == null || !DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException("'database' must be a plain database or service name.");
        }
        return database;
    }

    /** Keeps only allow-listed options with allow-listed values (case-insensitive keys). */
    public Map<String, String> options(Map<String, String> requested) {
        Map<String, String> accepted = new LinkedHashMap<>();
        if (requested == null) {
            return accepted;
        }
        Map<String, Set<String>> allowed = allowedOptions();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String key = allowed.keySet().stream()
                    .filter(k -> k.equalsIgnoreCase(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Database option '"
                            + Specs.safe(entry.getKey()) + "' is not allowed for " + name()
                            + "; allowed: " + String.join(", ", new java.util.TreeSet<>(allowed.keySet()))
                            + "."));
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            Set<String> values = allowed.get(key);
            boolean ok = values.isEmpty()
                    ? HOSTNAME_VALUE.matcher(value).matches()
                    : values.stream().anyMatch(v -> v.equalsIgnoreCase(value));
            if (!ok) {
                throw new IllegalArgumentException("Database option '" + key + "' has a value that "
                        + "is not allowed.");
            }
            accepted.put(key, value);
        }
        return accepted;
    }

    // ---- SQL ----------------------------------------------------------------

    public String quote(String identifier) {
        DbIdentifiers.require(identifier, "identifier");
        return open + identifier + close;
    }

    String quoteAll(List<String> identifiers) {
        return identifiers.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    /** {@code schema.table} (both validated and quoted) or {@code table}. */
    public String qualified(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    /**
     * A read of allow-listed identifiers only: the watermark comparison is a
     * bound parameter, ordering makes the watermark monotonic per page.
     */
    public String select(String table, List<String> columns, String watermark, boolean afterCursor) {
        String list = columns.isEmpty() ? "*" : quoteAll(columns);
        StringBuilder sql = new StringBuilder("SELECT ").append(list).append(" FROM ").append(table);
        if (watermark != null) {
            if (afterCursor) {
                sql.append(" WHERE ").append(quote(watermark)).append(" > ?");
            }
            sql.append(" ORDER BY ").append(quote(watermark));
        }
        return sql.toString();
    }

    public String insert(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + quoteAll(columns) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    String on(List<String> keys, String target, String source) {
        return keys.stream().map(k -> target + "." + quote(k) + " = " + source + "." + quote(k))
                .collect(Collectors.joining(" AND "));
    }

    String mergeTail(List<String> columns, List<String> keys, String target, String source) {
        List<String> updates = nonKey(columns, keys);
        StringBuilder tail = new StringBuilder();
        if (!updates.isEmpty()) {
            tail.append(" WHEN MATCHED THEN UPDATE SET ").append(updates.stream()
                    .map(c -> target + "." + quote(c) + " = " + source + "." + quote(c))
                    .collect(Collectors.joining(", ")));
        }
        tail.append(" WHEN NOT MATCHED THEN INSERT (").append(quoteAll(columns)).append(") VALUES (")
                .append(columns.stream().map(c -> source + "." + quote(c))
                        .collect(Collectors.joining(", ")))
                .append(")");
        return tail.toString();
    }

    static List<String> nonKey(List<String> columns, List<String> keys) {
        List<String> result = new ArrayList<>();
        for (String column : columns) {
            if (keys.stream().noneMatch(k -> k.equalsIgnoreCase(column))) {
                result.add(column);
            }
        }
        return result;
    }

    private static void appendQuery(StringBuilder url, Map<String, String> params, char first,
                                    char separator) {
        char next = first;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            url.append(next).append(entry.getKey()).append('=').append(entry.getValue());
            next = separator;
        }
    }
}
