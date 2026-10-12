package com.modocument.connector.core.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed, validating access to a flow's source/target spec and a
 * connection's config (ws25), the same on the server and in the agent. Every refusal is an IllegalArgumentException
 * whose message is safe to show the admin (it names the key, never a
 * secret).
 */
public final class Specs {

    /** A relative path segment chain: letters, digits, {@code _-.~/}, no "..". */
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[A-Za-z0-9_.~/-]{0,500}$");

    private Specs() {
    }

    /** Refuses keys this connector does not know (typos must not silently do nothing). */
    public static void allowOnly(Map<String, ?> map, String what, Set<String> allowed) {
        if (map == null) {
            return;
        }
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown " + what + " key '" + safe(key)
                        + "'; allowed: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ".");
            }
        }
    }

    public static String string(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map || value instanceof List) {
            throw new IllegalArgumentException("'" + key + "' must be a plain value.");
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static String requireString(Map<String, ?> map, String key, String what) {
        String value = string(map, key);
        if (value == null) {
            throw new IllegalArgumentException(what + " '" + key + "' is required.");
        }
        return value;
    }

    public static Integer integer(Map<String, ?> map, String key, int min, int max) {
        String value = string(map, key);
        if (value == null) {
            return null;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a whole number.");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("'" + key + "' must be between " + min + " and "
                    + max + ".");
        }
        return parsed;
    }

    public static <E extends Enum<E>> E enumValue(Map<String, ?> map, String key, Class<E> type,
                                                  E fallback) {
        String value = string(map, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + key + "' must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()) + ".");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("'" + key + "' must be an object.");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<Object, Object>) value).forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }

    public static List<String> stringList(Map<String, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + key + "' must be a list.");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null || item instanceof Map || item instanceof List) {
                throw new IllegalArgumentException("'" + key + "' must list plain names.");
            }
            out.add(String.valueOf(item).trim());
        }
        return out;
    }

    /**
     * A path RELATIVE to the configured base (URL base path or SFTP folder):
     * no scheme, no absolute path, no {@code ..}, no backslash, no query or
     * fragment, no control characters.
     */
    public static String relativePath(String value, String what, boolean leadingSlashAllowed) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String path = value;
        if (leadingSlashAllowed && path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith("/") || !RELATIVE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(what + " is not a plain path: it must be a relative "
                    + "path of letters, digits and _-.~/ only.");
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException(what + " is not a plain path: it must not contain "
                        + "'.' or '..' segments.");
            }
        }
        if (path.contains("//")) {
            throw new IllegalArgumentException(what + " is not a plain path: it must not contain "
                    + "empty segments.");
        }
        return path;
    }

    /** A key/name echoed in an error message: printable and short. */
    public static String safe(String value) {
        if (value == null) {
            return "null";
        }
        String printable = value.replaceAll("[^\\x20-\\x7E]", "?");
        return printable.length() <= 64 ? printable : printable.substring(0, 64) + "...";
    }
}
