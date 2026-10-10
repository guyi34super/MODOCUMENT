package com.modocument.infrastructure.file;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Stores uploaded files under the configured storage root (uploads/) with the
 * same timestamp-suffix naming the original upload endpoint used; shared by
 * the upload endpoint and ingestion job creation.
 */
@Component
public class UploadStorage {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Leaves room for "_yyyyMMddHHmmss" and the extension inside varchar(255). */
    static final int MAX_BASE_NAME_LENGTH = 200;
    static final int MAX_EXTENSION_LENGTH = 20;

    private final Path uploadsDir;

    public UploadStorage(StorageProperties storage) {
        this.uploadsDir = storage.uploadsDir();
    }

    /** Saves the file and returns the stored (timestamped) file name. */
    public String store(MultipartFile file) {
        try {
            Path target = newTarget(file.getOriginalFilename());
            file.transferTo(target.toAbsolutePath());
            return target.getFileName().toString();
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
            Path target = newTarget(originalFileName);
            Files.write(target, content);
            return target.getFileName().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the delivered file.", e);
        }
    }

    /** A fresh, collision-free stored name in uploads/ (timestamp suffix, length-capped). */
    private Path newTarget(String original) throws IOException {
        {
            Files.createDirectories(uploadsDir);

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
            // FileName is a varchar(255) in every table that records it, and the
            // suffix plus extension has to fit too — truncating here beats an
            // insert failing after the bytes are already on disk.
            baseName = truncate(baseName, MAX_BASE_NAME_LENGTH);
            extension = truncate(extension, MAX_EXTENSION_LENGTH);

            String stamp = LocalDateTime.now().format(TIMESTAMP);
            String storedName = baseName + "_" + stamp + extension;
            // Same base name in the same second (typical in a bulk photo
            // upload) must not collapse onto one file: two records pointing at
            // the same bytes means deleting one silently breaks the other.
            for (int attempt = 2; Files.exists(uploadsDir.resolve(storedName)); attempt++) {
                storedName = baseName + "_" + stamp + "-" + attempt + extension;
            }
            return uploadsDir.resolve(storedName);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Resolves a previously stored file name inside the uploads folder (no traversal). */
    public Path resolve(String fileName) {
        Path candidate = uploadsDir.resolve(fileName).normalize();
        if (!candidate.startsWith(uploadsDir)) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }
        return candidate;
    }
}
