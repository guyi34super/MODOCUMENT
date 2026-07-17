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
 * Stores uploaded files under wwwroot/uploads with the same timestamp-suffix
 * naming the original upload endpoint used; shared by the upload endpoint and
 * ingestion job creation.
 */
@Component
public class UploadStorage {

    /** Uploaded files live under ./wwwroot/uploads, same location the original backend used. */
    public static final Path UPLOADS_DIR = Path.of("wwwroot", "uploads");

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Saves the file and returns the stored (timestamped) file name. */
    public String store(MultipartFile file) {
        try {
            Files.createDirectories(UPLOADS_DIR);

            String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
            // Keep only the last path segment; browsers/clients may send paths.
            originalName = Path.of(originalName).getFileName().toString();
            String baseName = originalName;
            String extension = "";
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) {
                baseName = originalName.substring(0, dot);
                extension = originalName.substring(dot);
            }

            String storedName = baseName + "_" + LocalDateTime.now().format(TIMESTAMP) + extension;
            file.transferTo(UPLOADS_DIR.resolve(storedName).toAbsolutePath());
            return storedName;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store uploaded file.", e);
        }
    }

    /** Resolves a previously stored file name inside the uploads folder (no traversal). */
    public Path resolve(String fileName) {
        Path candidate = UPLOADS_DIR.resolve(fileName).normalize();
        if (!candidate.startsWith(UPLOADS_DIR)) {
            throw new IllegalArgumentException("Invalid file name: " + fileName);
        }
        return candidate;
    }
}
