package com.modocument.api.controller;

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

    public FileUploadController(UploadStorage uploadStorage) {
        this.uploadStorage = uploadStorage;
    }

    @PostMapping(value = "/api/fileupload/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadFile(@RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded.");
        }

        String storedName = uploadStorage.store(file);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", storedName);
        response.put("name", storedName);
        response.put("length", file.getSize());
        response.put("filePath", uploadStorage.resolve(storedName).toString());
        return ResponseEntity.ok(response);
    }
}
