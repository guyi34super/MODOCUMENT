package com.modocument.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Where uploaded files, export copies and document assets live (optimization
 * ws29, SE5): local disk for dev/tests, S3 on ECS so files survive task
 * replacement and are shared by every task.
 *
 * <p>Keys are relative, slash-separated paths in the SAME layout the disk
 * storage uses today ({@code uploads/<storedName>},
 * {@code exports/u<owner>/job<id>.<ext>}, ...). Implementations reject
 * absolute keys, {@code ..} segments and backslashes. Tenant isolation stays
 * with the database rows (RLS) that reference a key; a key alone never
 * grants access.
 */
public interface FileStore {

    /** Stores {@code length} bytes under {@code key}, replacing any existing object. */
    void put(String key, InputStream content, long length, String contentType) throws IOException;

    /** @throws java.nio.file.NoSuchFileException when the key does not exist */
    InputStream open(String key) throws IOException;

    /**
     * A readable local file for libraries that need a {@link Path} (POI,
     * PDFBox, LibreOffice). Local storage returns the file itself; S3
     * downloads to a bounded temp cache. Callers never write to or delete it.
     *
     * @throws java.nio.file.NoSuchFileException when the key does not exist
     */
    Path localCopy(String key) throws IOException;

    boolean exists(String key) throws IOException;

    /** Deleting a missing key is not an error. */
    void delete(String key) throws IOException;

    /** Keys starting with {@code prefix}, for retention sweeps and DSR exports. */
    List<String> list(String prefix) throws IOException;
}
