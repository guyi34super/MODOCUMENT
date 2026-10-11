package com.modocument.api.controller;

import com.modocument.api.config.DeprecatedEndpoint;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.usecase.UploadOwnership;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.file.SupportedDocumentTypes;
import com.modocument.infrastructure.file.UploadContentChecker;
import com.modocument.infrastructure.file.UploadStorage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "File Upload", description = "Multipart data-file uploads")
@RequiresPermission(Permission.FILE_UPLOAD)
public class FileUploadController {

    private final UploadStorage uploadStorage;
    private final SupportedDocumentTypes supportedTypes;
    private final UploadOwnership uploadOwnership;
    private final UploadContentChecker contentChecker;

    public FileUploadController(UploadStorage uploadStorage,
                                SupportedDocumentTypes supportedTypes,
                                UploadOwnership uploadOwnership,
                                UploadContentChecker contentChecker) {
        this.uploadStorage = uploadStorage;
        this.supportedTypes = supportedTypes;
        this.uploadOwnership = uploadOwnership;
        this.contentChecker = contentChecker;
    }

    @DeprecatedEndpoint(replacement = "POST /api/ingestion/jobs (multipart)")
    @PostMapping(value = "/api/fileupload/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadFile(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            // A JSON body: a bare string degrades to "Request failed (400)" in
            // the client, which reads as a bug rather than a missing file.
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded."));
        }
        // Same gate as the ingestion upload — accepting a file the pipeline
        // cannot parse only moves the failure somewhere less explainable.
        if (!supportedTypes.isSupported(file.getOriginalFilename())) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Unsupported file type. Supported types: "
                            + supportedTypes.describeSupported() + "."));
        }
        // The content must look like what the extension claims (SEC-030): a
        // renamed executable must not enter the pipeline as a ".csv".
        contentChecker.requireContentMatchesExtension(file.getOriginalFilename(), file);

        // Duplicate gate (C6): same owner + same bytes + same original name is
        // a re-upload, refused with 409 before anything lands on disk.
        String sha256Hex = sha256(file);
        uploadOwnership.rejectDuplicate(file.getOriginalFilename(), sha256Hex);

        String storedName = uploadStorage.store(file);
        // Ownership row (V20): every later reference to this stored name is
        // scoped to the uploader.
        uploadOwnership.record(storedName, file.getOriginalFilename(), file.getSize(), sha256Hex);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", storedName);
        response.put("name", storedName);
        response.put("length", file.getSize());
        return ResponseEntity.ok(response);
    }

    static String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return Hashing.sha256(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file.", e);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
