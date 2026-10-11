package com.modocument.infrastructure.file;

import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.store.FileStoreKeys;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stores uploaded files under {@code uploads/} in the {@link FileStore} with
 * the same timestamp-suffix naming the original upload endpoint used; shared
 * by the upload endpoint, ingestion job creation, integration pulls and
 * doc-creation (blueprints, image assets).
 */
@Component
public class UploadStorage {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Leaves room for "_yyyyMMddHHmmss" and the extension inside varchar(255). */
    static final int MAX_BASE_NAME_LENGTH = 200;
    static final int MAX_EXTENSION_LENGTH = 20;

    private final FileStore files;

    public UploadStorage(FileStore files) {
        this.files = files;
    }

    /** Saves the file and returns the stored (timestamped) file name. */
    public String store(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String storedName = newStoredName(file.getOriginalFilename());
            files.put(key(storedName), in, file.getSize(), file.getContentType());
            return storedName;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store uploaded file.", e);
        }
    }

    /**
     * ws25: saves bytes a machine delivered (an integration flow's SFTP pull)
     * under the same naming rules; returns the stored file name.
     */
    public String store(String originalFileName, byte[] content) {
        try {
            String storedName = newStoredName(originalFileName);
            files.put(key(storedName), new ByteArrayInputStream(content), content.length, null);
            return storedName;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the delivered file.", e);
        }
    }

    /**
     * The store key of a previously stored file name ({@code uploads/<name>}).
     * Names that are not a single plain segment ({@code ../x}, {@code a/b},
     * absolute paths, backslashes) are refused — no traversal out of uploads.
     *
     * @throws IllegalArgumentException for an unsafe name
     */
    public String key(String fileName) {
        try {
            return FileStoreKeys.upload(fileName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid file name: " + fileName, e);
        }
    }

    /**
     * A readable local file for parsers (POI, PDFBox, LibreOffice): the file
     * itself on local storage, a cached download on S3. Never write to it.
     *
     * @throws java.nio.file.NoSuchFileException when nothing is stored under the name
     */
    public Path localCopy(String fileName) throws IOException {
        return files.localCopy(key(fileName));
    }

    public boolean exists(String fileName) {
        try {
            return files.exists(key(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not check the stored file.", e);
        }
    }

    public byte[] readAllBytes(String fileName) throws IOException {
        try (InputStream in = files.open(key(fileName))) {
            return in.readAllBytes();
        }
    }

    /** A fresh, collision-free stored name in uploads/ (timestamp suffix, length-capped). */
    private String newStoredName(String original) throws IOException {
        String originalName = original == null ? "file" : original;
        // Keep only the last path segment; browsers/clients may send paths.
        // getFileName() is null for a bare "/" or "C:\\", hence the fallback.
        Path lastSegment = Path.of(originalName).getFileName();
        originalName = lastSegment == null ? "file" : lastSegment.toString();
        String baseName = originalName;
        String extension = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) {
            baseName = originalName.substring(0, dot);
            extension = originalName.substring(dot);
        }
        if (baseName.isBlank()) {
            baseName = "file";
        }
        // A backslash is a plain character on Linux but never a valid store
        // key segment (FileStoreKeys): keep the part after the last one.
        baseName = afterLastBackslash(baseName);
        extension = afterLastBackslash(extension);
        if (baseName.isBlank()) {
            baseName = "file";
        }
        // FileName is a varchar(255) in every table that records it, and the
        // suffix plus extension has to fit too — truncating here beats an
        // insert failing after the bytes are already stored.
        baseName = truncate(baseName, MAX_BASE_NAME_LENGTH);
        extension = truncate(extension, MAX_EXTENSION_LENGTH);

        String stamp = LocalDateTime.now().format(TIMESTAMP);
        String storedName = baseName + "_" + stamp + extension;
        // Same base name in the same second (typical in a bulk photo
        // upload) must not collapse onto one file: two records pointing at
        // the same bytes means deleting one silently breaks the other.
        for (int attempt = 2; files.exists(key(storedName)); attempt++) {
            storedName = baseName + "_" + stamp + "-" + attempt + extension;
        }
        return storedName;
    }

    private static String afterLastBackslash(String value) {
        int backslash = value.lastIndexOf('\\');
        return backslash < 0 ? value : value.substring(backslash + 1);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
