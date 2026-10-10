package com.modocument.agent.allowlist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The BANK-controlled allow-list (a YAML file the bank mounts read-only).
 * MoDocument decides what to run; this file decides what the agent is
 * willing to touch, so a compromised cloud account still cannot make the
 * agent read an arbitrary table, host or folder. Every task is checked
 * before any connection is opened; anything not listed is refused.
 *
 * <pre>
 * destinations:            # host + ports every connection may reach
 *   - host: db.bank.local
 *     ports: [5432]
 * database:
 *   tables:                # [schema.]table, case-insensitive, exact
 *     - modocument.v_customers_export
 * sftp:
 *   folders:               # absolute; sub-folders included
 *     - /exports/modocument
 * rest:
 *   pathPrefixes:          # optional; absent = any path on an allowed host
 *     - /api/v1/
 * </pre>
 *
 * A missing or unreadable file means an EMPTY list: every task is refused
 * (fail closed). The file is re-read when its modification time changes.
 */
public class AllowList {

    private static final Logger log = LoggerFactory.getLogger(AllowList.class);

    private final Path file;
    private volatile Rules rules = Rules.EMPTY;
    private volatile FileTime loadedAt;

    public AllowList(Path file) {
        this.file = file;
        reloadIfChanged();
    }

    /** Refuses a destination host:port not listed. */
    public void checkDestination(String host, int port) {
        Rules current = current();
        String wanted = normalizeHost(host);
        List<Integer> ports = current.destinations().get(wanted);
        if (ports == null || !ports.contains(port)) {
            throw new AllowListViolation("destination " + wanted + ":" + port + " is not allowed");
        }
    }

    /** Refuses a table not listed (qualified name compared case-insensitively). */
    public void checkTable(String qualifiedTable) {
        String wanted = qualifiedTable == null ? "" : qualifiedTable.toLowerCase(Locale.ROOT);
        if (!current().tables().contains(wanted)) {
            throw new AllowListViolation("table " + qualifiedTable + " is not allowed");
        }
    }

    /** Refuses an SFTP folder outside every listed folder (already normalized, absolute). */
    public void checkFolder(String folder) {
        for (String allowed : current().folders()) {
            if (folder.equals(allowed) || folder.startsWith(allowed.endsWith("/") ? allowed : allowed + "/")) {
                return;
            }
        }
        throw new AllowListViolation("folder " + folder + " is not allowed");
    }

    /** Refuses a REST path outside the listed prefixes (when any are listed). */
    public void checkRestPath(String path) {
        List<String> prefixes = current().restPrefixes();
        if (prefixes.isEmpty()) {
            return;
        }
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return;
            }
        }
        throw new AllowListViolation("REST path " + path + " is not allowed");
    }

    /** Whether anything at all is allowed (startup warning). */
    public boolean isEmpty() {
        return current().destinations().isEmpty();
    }

    private Rules current() {
        reloadIfChanged();
        return rules;
    }

    private synchronized void reloadIfChanged() {
        try {
            if (!Files.isRegularFile(file)) {
                if (loadedAt != null || rules != Rules.EMPTY) {
                    log.warn("Allow-list {} disappeared: every task is refused", file);
                }
                rules = Rules.EMPTY;
                loadedAt = null;
                return;
            }
            FileTime modified = Files.getLastModifiedTime(file);
            if (modified.equals(loadedAt)) {
                return;
            }
            rules = parse(file);
            loadedAt = modified;
            log.info("Allow-list loaded from {}: {} destination(s), {} table(s), {} folder(s)", file,
                    rules.destinations().size(), rules.tables().size(), rules.folders().size());
        } catch (IOException | RuntimeException e) {
            log.error("Allow-list {} is invalid ({}): every task is refused", file, e.getMessage());
            rules = Rules.EMPTY;
            loadedAt = null;
        }
    }

    private static Rules parse(Path file) throws IOException {
        Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        }
        if (root == null) {
            return Rules.EMPTY;
        }
        Map<?, ?> top = asMap(root, "the allow-list");
        Map<String, List<Integer>> destinations = new LinkedHashMap<>();
        for (Object entry : asList(top.get("destinations"), "destinations")) {
            Map<?, ?> destination = asMap(entry, "a destination");
            String host = normalizeHost(String.valueOf(destination.get("host")));
            if (host.isBlank() || "null".equals(host)) {
                throw new IllegalArgumentException("a destination has no host");
            }
            List<Integer> ports = new ArrayList<>();
            for (Object port : asList(destination.get("ports"), "ports of " + host)) {
                int value = Integer.parseInt(String.valueOf(port));
                if (value < 1 || value > 65535) {
                    throw new IllegalArgumentException("port " + value + " of " + host);
                }
                ports.add(value);
            }
            destinations.computeIfAbsent(host, h -> new ArrayList<>()).addAll(ports);
        }
        Set<String> tables = new HashSet<>();
        Map<?, ?> database = top.get("database") == null ? Map.of() : asMap(top.get("database"), "database");
        for (Object table : asList(database.get("tables"), "database.tables")) {
            tables.add(String.valueOf(table).strip().toLowerCase(Locale.ROOT));
        }
        List<String> folders = new ArrayList<>();
        Map<?, ?> sftp = top.get("sftp") == null ? Map.of() : asMap(top.get("sftp"), "sftp");
        for (Object folder : asList(sftp.get("folders"), "sftp.folders")) {
            String value = String.valueOf(folder).strip();
            if (!value.startsWith("/") || value.contains("..")) {
                throw new IllegalArgumentException("sftp folder " + value + " must be absolute");
            }
            folders.add(value.length() > 1 && value.endsWith("/")
                    ? value.substring(0, value.length() - 1) : value);
        }
        List<String> prefixes = new ArrayList<>();
        Map<?, ?> rest = top.get("rest") == null ? Map.of() : asMap(top.get("rest"), "rest");
        for (Object prefix : asList(rest.get("pathPrefixes"), "rest.pathPrefixes")) {
            String value = String.valueOf(prefix).strip();
            if (!value.startsWith("/")) {
                throw new IllegalArgumentException("rest path prefix " + value + " must start with /");
            }
            prefixes.add(value);
        }
        return new Rules(Map.copyOf(destinations), Set.copyOf(tables), List.copyOf(folders),
                List.copyOf(prefixes));
    }

    private static Map<?, ?> asMap(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(what + " must be a mapping");
    }

    private static List<?> asList(Object value, String what) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException(what + " must be a list");
    }

    private static String normalizeHost(String host) {
        return host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
    }

    private record Rules(Map<String, List<Integer>> destinations, Set<String> tables,
                         List<String> folders, List<String> restPrefixes) {
        static final Rules EMPTY = new Rules(Map.of(), Set.of(), List.of(), List.of());
    }
}
