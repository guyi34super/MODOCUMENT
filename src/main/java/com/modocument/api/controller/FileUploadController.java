package com.modocument.api.controller;

import com.modocument.infrastructure.file.SupportedDocumentTypes;
import com.modocument.infrastructure.file.UploadStorage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class FileUploadController {

    private final UploadStorage uploadStorage;
    private final SupportedDocumentTypes supportedTypes;

    public FileUploadController(UploadStorage uploadStorage,
                                SupportedDocumentTypes supportedTypes) {
        this.uploadStorage = uploadStorage;
        this.supportedTypes = supportedTypes;
    }

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

        String storedName = uploadStorage.store(file);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", storedName);
        response.put("name", storedName);
        response.put("length", file.getSize());
        return ResponseEntity.ok(response);
    }
}
