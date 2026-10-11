package com.modocument.infrastructure.file.store;

/**
 * Key rules and the key layout of the {@link com.modocument.application.port.FileStore}
 * (optimization ws29). Keys keep the relative layout the disk storage has
 * always used, so a local deployment reads its existing files unchanged and
 * an S3 bucket mirrors the same tree:
 * <ul>
 *   <li>{@code uploads/<storedName>}: uploaded files, doc-creation
 *       blueprints and image assets</li>
 *   <li>{@code exports/<name>}: generated doc-creation documents</li>
 *   <li>{@code exports/u<owner>/job<id>.<ext>}: ingestion export archive copies</li>
 * </ul>
 */
public final class FileStoreKeys {

    public static final String UPLOADS = "uploads/";
    public static final String EXPORTS = "exports/";

    private static final int MAX_KEY_LENGTH = 1024;

    private FileStoreKeys() {
    }

    /**
     * Returns the key unchanged when it is a safe relative key; rejects
     * blank, absolute ({@code /x}, {@code C:...}), backslashed, NUL-carrying
     * and traversal ({@code ..}, {@code .}, empty segment) keys.
     *
     * @throws IllegalArgumentException when the key is unsafe
     */
    public static String validate(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A storage key is required.");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Storage key is too long.");
        }
        if (key.startsWith("/") || key.indexOf('\\') >= 0 || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        if (key.length() >= 2 && key.charAt(1) == ':' && Character.isLetter(key.charAt(0))) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid storage key: " + key);
            }
        }
        return key;
    }

    /**
     * Validates a listing prefix: empty (everything) or a key-shaped prefix,
     * optionally ending in {@code /}.
     */
    public static String validatePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        validate(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        return prefix;
    }

    /** {@code uploads/<storedName>}; the stored name must be one path segment. */
    public static String upload(String storedName) {
        return singleSegment(UPLOADS, storedName);
    }

    /**
     * {@link #upload} for bulk destruction paths (retention purge, DSR erase):
     * empty for a legacy stored name the store cannot address (one written
     * before keys were validated, e.g. holding a backslash), so one odd row
     * never blocks a whole bank's purge. The caller logs the skip.
     */
    public static java.util.Optional<String> tryUpload(String storedName) {
        return tryKey(() -> upload(storedName));
    }

    /** {@link #export} counterpart of {@link #tryUpload}. */
    public static java.util.Optional<String> tryExport(String name) {
        return tryKey(() -> export(name));
    }

    private static java.util.Optional<String> tryKey(java.util.function.Supplier<String> key) {
        try {
            return java.util.Optional.of(key.get());
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /** {@code exports/<name>} for a generated doc-creation document. */
    public static String export(String name) {
        return singleSegment(EXPORTS, name);
    }

    /** {@code exports/u<owner>/job<id><extension>} — an ingestion export archive copy. */
    public static String exportCopy(int ownerUserId, int jobId, String extension) {
        return validate(EXPORTS + "u" + ownerUserId + "/job" + jobId + extension);
    }

    private static String singleSegment(String folder, String name) {
        if (name == null || name.isBlank() || name.indexOf('/') >= 0) {
            return invalid(name);
        }
        return validate(folder + name);
    }

    private static String invalid(String name) {
        throw new IllegalArgumentException("Invalid stored file name: " + name);
    }
}
