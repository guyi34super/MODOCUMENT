package com.modocument.api.controller;

import com.modocument.infrastructure.transform.TransformServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class FileUploadController {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @PostMapping(value = "/api/fileupload/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadFile(@RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file uploaded.");
        }

        Path uploadsFolder = TransformServiceImpl.UPLOADS_DIR;
        Files.createDirectories(uploadsFolder);

        String originalName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String baseName = originalName;
        String extension = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) {
            baseName = originalName.substring(0, dot);
            extension = originalName.substring(dot);
        }

        String fileNameWithDateTime = baseName + "_" + LocalDateTime.now().format(TIMESTAMP) + extension;
        Path filePath = uploadsFolder.resolve(fileNameWithDateTime);
        file.transferTo(filePath.toAbsolutePath());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fileName", fileNameWithDateTime);
        response.put("name", fileNameWithDateTime);
        response.put("length", file.getSize());
        response.put("filePath", filePath.toString());
        return ResponseEntity.ok(response);
    }
}
