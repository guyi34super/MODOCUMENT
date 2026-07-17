package com.modocument.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TransformService;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.entity.ImportHistory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
public class TransformController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransformService transformService;
    private final TemplateFieldsRepository templateFieldsRepository;
    private final ImportHistoryRepository importHistoryRepository;
    private final ImportDetailsRepository importDetailsRepository;

    public TransformController(TransformService transformService,
                               TemplateFieldsRepository templateFieldsRepository,
                               ImportHistoryRepository importHistoryRepository,
                               ImportDetailsRepository importDetailsRepository) {
        this.transformService = transformService;
        this.templateFieldsRepository = templateFieldsRepository;
        this.importHistoryRepository = importHistoryRepository;
        this.importDetailsRepository = importDetailsRepository;
    }

    @PostMapping("/api/transform/transform")
    public ResponseEntity<?> transformFile(@RequestBody(required = false) TransformRequest request)
            throws JsonProcessingException {
        if (request == null || request.getFileName() == null || request.getFileName().isBlank()) {
            return ResponseEntity.badRequest().body("FileName is required in the request body.");
        }

        try {
            int templateId = transformService.getTemplateId(request);

            List<String> fieldNames = templateFieldsRepository.list().stream()
                    .filter(tf -> tf.getTemplateId() == templateId)
                    .map(tf -> tf.getFieldName() == null ? "" : tf.getFieldName())
                    .toList();
            if (fieldNames.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("No template fields found for TemplateId: " + templateId);
            }

            String model = MAPPER.writeValueAsString(fieldNames);

            TransformResult resultDto = transformService.transform(request, model);

            ImportHistory importHistory = new ImportHistory();
            importHistory.setCompanyId(1000);
            importHistory.setTemplateId(templateId);
            importHistory.setFileName(request.getFileName());
            importHistory.setImportDate(LocalDateTime.now(ZoneOffset.UTC));
            ImportHistory saved = importHistoryRepository.add(importHistory);

            ImportDetails details = new ImportDetails();
            details.setImportHistoryId(saved.getImportHistoryId());
            details.setRawJson(resultDto.getJsonPayload());
            details.setProcessStepId(1);
            importDetailsRepository.add(details);

            return ResponseEntity.ok(resultDto);
        } catch (NoSuchElementException notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound.getMessage());
        } catch (UnsupportedOperationException unsupported) {
            return ResponseEntity.badRequest().body(unsupported.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred: " + ex.getMessage());
        }
    }
}
