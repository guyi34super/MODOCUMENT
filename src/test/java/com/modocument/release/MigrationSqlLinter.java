package com.modocument.release;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static expand/contract lint for Flyway SQL (ws24,
 * docs/release/migration-policy.md). Text-based on purpose: no database,
 * no SQL parser dependency. It strips comments and string literals, keeps
 * the bodies of dollar-quoted blocks (a {@code DO $$ … $$} block runs DDL
 * at migration time, so it is checked too) and looks for the statements
 * that would break the PREVIOUS release's image if it had to be rolled back
 * onto the migrated schema.
 *
 * <p>Two kinds of finding:
 * <ul>
 *   <li><b>contract</b> — destructive or narrowing steps (drops, renames,
 *       type changes, {@code SET NOT NULL}, {@code VALIDATE CONSTRAINT}).
 *       Allowed only in a file headed {@code -- contract: expanded-in vX.Y.Z},
 *       i.e. one release after the expand step shipped.</li>
 *   <li><b>always</b> — never allowed, header or not: {@code ADD COLUMN …
 *       NOT NULL} without a {@code DEFAULT} (the previous release's INSERTs
 *       do not know the column).</li>
 * </ul>
 * A {@code DROP CONSTRAINT name} later re-added in the same file
 * ({@code ADD CONSTRAINT name}) is a constraint replacement — e.g. widening
 * a CHECK — and not a finding; likewise a {@code DROP VIEW name} re-created
 * by name.
 */
final class MigrationSqlLinter {

    /** One finding: the rule, whether a contract header exempts it, and where. */
    record Finding(String rule, boolean contract, String statement) {
        @Override
        public String toString() {
            return (contract ? "[needs contract header] " : "[never allowed] ") + rule + ": " + statement;
        }
    }

    static final Pattern CONTRACT_HEADER = Pattern.compile(
            "^--\\s*contract:\\s*expanded-in\\s+v(\\d+\\.\\d+\\.\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_CONTRACT_MENTION = Pattern.compile(
            "^--\\s*contract\\b", Pattern.CASE_INSENSITIVE);

    private static final String IDENT = "[A-Z0-9_$]+";
    private static final String QUALIFIED = "[A-Z0-9_$.]+";

    private static final Pattern DROP_TABLE = Pattern.compile("\\bDROP\\s+TABLE\\b");
    private static final Pattern DROP_COLUMN = Pattern.compile("\\bDROP\\s+COLUMN\\b");
    /** {@code ALTER TABLE t DROP c} / {@code …, DROP c}: COLUMN is optional in PostgreSQL. */
    private static final Pattern DROP_COLUMN_IMPLICIT = Pattern.compile(
            "(?:\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?[^\\s,]+(?:\\s*\\*)?\\s+|,\\s*)"
                    + "DROP\\s+(?!CONSTRAINT\\b|COLUMN\\b)");
    private static final Pattern DROP_CONSTRAINT = Pattern.compile(
            "\\bDROP\\s+CONSTRAINT\\s+(?:IF\\s+EXISTS\\s+)?(" + IDENT + ")");
    private static final Pattern DROP_VIEW = Pattern.compile(
            "\\bDROP\\s+(MATERIALIZED\\s+)?VIEW\\s+(?:IF\\s+EXISTS\\s+)?(" + QUALIFIED + ")");
    private static final Pattern RENAME = Pattern.compile("\\bRENAME\\b");
    private static final Pattern ALTER_COLUMN_TYPE = Pattern.compile(
            "\\bALTER\\s+(?:COLUMN\\s+)?(?!COLUMN\\b|TABLE\\b|INDEX\\b|SEQUENCE\\b|VIEW\\b|DOMAIN\\b"
                    + "|SCHEMA\\b|FUNCTION\\b)" + IDENT + "\\s+(?:SET\\s+DATA\\s+)?TYPE\\b");
    private static final Pattern SET_NOT_NULL = Pattern.compile("\\bSET\\s+NOT\\s+NULL\\b");
    private static final Pattern VALIDATE_CONSTRAINT = Pattern.compile("\\bVALIDATE\\s+CONSTRAINT\\b");

    private static final Pattern ALTER_TABLE_PREFIX = Pattern.compile(
            "^.*?\\bALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?[^\\s,]+(?:\\s*\\*)?\\s+");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(?!CONSTRAINT\\b|PRIMARY\\b|UNIQUE\\b|CHECK\\b|FOREIGN\\b|EXCLUDE\\b)"
                    + IDENT + "\\s+(.*)$");
    private static final Pattern NOT_NULL = Pattern.compile("\\bNOT\\s+NULL\\b");
    private static final Pattern HAS_VALUE = Pattern.compile("\\bDEFAULT\\b|\\bGENERATED\\b|SERIAL\\b");

    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    private MigrationSqlLinter() {
    }

    /** The version named by a well-formed contract header, if the file has one. */
    static Optional<String> contractHeader(String sql) {
        for (String line : headerLines(sql)) {
            Matcher header = CONTRACT_HEADER.matcher(line.strip());
            if (header.matches()) {
                return Optional.of(header.group(1));
            }
        }
        return Optional.empty();
    }

    /** True when a leading comment mentions "contract" but is not a well-formed header. */
    static boolean hasMalformedContractHeader(String sql) {
        return contractHeader(sql).isEmpty()
                && headerLines(sql).stream().anyMatch(line -> ANY_CONTRACT_MENTION.matcher(line.strip()).find());
    }

    /** Every finding in the file, in statement order (header not considered). */
    static List<Finding> lint(String sql) {
        String text = normalize(sanitize(sql));
        Set<Finding> findings = new LinkedHashSet<>();

        for (String statement : text.split(";")) {
            String s = statement.strip();
            if (s.isEmpty()) {
                continue;
            }
            check(findings, s, DROP_TABLE, "DROP TABLE");
            check(findings, s, DROP_COLUMN, "DROP COLUMN");
            if (s.contains("ALTER TABLE")) {
                check(findings, s, DROP_COLUMN_IMPLICIT, "DROP COLUMN (implicit)");
            }
            check(findings, s, RENAME, "RENAME");
            check(findings, s, ALTER_COLUMN_TYPE, "ALTER COLUMN ... TYPE");
            check(findings, s, SET_NOT_NULL, "SET NOT NULL");
            check(findings, s, VALIDATE_CONSTRAINT, "VALIDATE CONSTRAINT");
            if (s.contains("ALTER TABLE")) {
                addColumnNotNullWithoutDefault(findings, s);
            }
        }

        // Replacements: dropped and re-created by name later in the same file.
        Matcher constraint = DROP_CONSTRAINT.matcher(text);
        while (constraint.find()) {
            String name = constraint.group(1);
            Pattern readded = Pattern.compile("\\bADD\\s+CONSTRAINT\\s+" + Pattern.quote(name) + "\\b");
            if (!readded.matcher(text).region(constraint.end(), text.length()).find()) {
                findings.add(new Finding("DROP CONSTRAINT " + name + " (not re-added in this file)",
                        true, excerpt(text, constraint.start())));
            }
        }
        Matcher view = DROP_VIEW.matcher(text);
        while (view.find()) {
            String name = view.group(2);
            Pattern recreated = Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?" + Pattern.quote(name) + "\\b");
            if (!recreated.matcher(text).region(view.end(), text.length()).find()) {
                findings.add(new Finding("DROP VIEW " + name + " (not re-created in this file)",
                        true, excerpt(text, view.start())));
            }
        }
        return new ArrayList<>(findings);
    }

    private static void check(Set<Finding> findings, String statement, Pattern pattern, String rule) {
        if (pattern.matcher(statement).find()) {
            findings.add(new Finding(rule, true, abbreviate(statement)));
        }
    }

    private static void addColumnNotNullWithoutDefault(Set<Finding> findings, String statement) {
        for (String action : topLevelCommaSplit(statement)) {
            String a = ALTER_TABLE_PREFIX.matcher(action.strip()).replaceFirst("").strip();
            Matcher add = ADD_COLUMN.matcher(a);
            if (add.matches() && NOT_NULL.matcher(add.group(1)).find()
                    && !HAS_VALUE.matcher(add.group(1)).find()) {
                findings.add(new Finding("ADD COLUMN ... NOT NULL without DEFAULT", false,
                        abbreviate(statement)));
            }
        }
    }

    private static List<String> topLevelCommaSplit(String statement) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                parts.add(statement.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(statement.substring(start));
        return parts;
    }

    /** Leading comment/blank lines, up to the first SQL line. */
    private static List<String> headerLines(String sql) {
        List<String> lines = new ArrayList<>();
        for (String line : sql.split("\\R")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("--")) {
                lines.add(s);
            } else {
                break;
            }
        }
        return lines;
    }

    /**
     * Comments removed, string literals emptied ({@code ''}), quoted
     * identifiers unquoted, dollar-quoted bodies kept (and sanitized
     * themselves).
     */
    static String sanitize(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol;
            } else if (c == '/' && next == '*') {
                int depth = 1;
                i += 2;
                while (i < n && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append(' ');
            } else if (c == '\'') {
                boolean escapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !Character.isLetterOrDigit(sql.charAt(i - 2)));
                i++;
                while (i < n) {
                    char s = sql.charAt(i);
                    if (escapes && s == '\\') {
                        i += 2;
                    } else if (s == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                    } else if (s == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                out.append("''");
            } else if (c == '"') {
                i++;
                while (i < n) {
                    char s = sql.charAt(i);
                    if (s == '"' && i + 1 < n && sql.charAt(i + 1) == '"') {
                        out.append('"');
                        i += 2;
                    } else if (s == '"') {
                        i++;
                        break;
                    } else {
                        out.append(s);
                        i++;
                    }
                }
            } else if (c == '$' && (i == 0 || !isIdentifierChar(sql.charAt(i - 1)))) {
                Matcher tag = DOLLAR_TAG.matcher(sql).region(i, n);
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int bodyStart = tag.end();
                    int bodyEnd = sql.indexOf(delimiter, bodyStart);
                    if (bodyEnd < 0) {
                        bodyEnd = n;
                    }
                    out.append(' ').append(sanitize(sql.substring(bodyStart, bodyEnd))).append(' ');
                    i = Math.min(n, bodyEnd + delimiter.length());
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String normalize(String sanitized) {
        return sanitized.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String excerpt(String text, int from) {
        int end = text.indexOf(';', from);
        return abbreviate(text.substring(from, end < 0 ? text.length() : end).strip());
    }

    private static String abbreviate(String statement) {
        return statement.length() <= 160 ? statement : statement.substring(0, 157) + "...";
    }
}
