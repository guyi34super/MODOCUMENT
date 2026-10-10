package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.TransformRequest;
import com.modocument.application.dto.TransformResult;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TransformService;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.application.usecase.UploadOwnership;
import com.modocument.domain.entity.ImportDetails;
import com.modocument.domain.model.Permission;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.infrastructure.ai.AiNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@Tag(name = "Transform", description = "Document-to-table extraction")
@RequiresPermission(Permission.AI_USE)
public class TransformController {

    private static final Logger log = LoggerFactory.getLogger(TransformController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransformService transformService;
    private final TemplateFieldsRepository templateFieldsRepository;
    private final ImportHistoryRepository importHistoryRepository;
    private final ImportDetailsRepository importDetailsRepository;
    private final TemplateOwnership ownership;
    private final UploadOwnership uploadOwnership;

    public TransformController(TransformService transformService,
                               TemplateFieldsRepository templateFieldsRepository,
                               ImportHistoryRepository importHistoryRepository,
                               ImportDetailsRepository importDetailsRepository,
                               TemplateOwnership ownership,
                               UploadOwnership uploadOwnership) {
        this.transformService = transformService;
        this.templateFieldsRepository = templateFieldsRepository;
        this.importHistoryRepository = importHistoryRepository;
        this.importDetailsRepository = importDetailsRepository;
        this.ownership = ownership;
        this.uploadOwnership = uploadOwnership;
    }

    @PostMapping("/api/transform/transform")
    public ResponseEntity<?> transformFile(@Valid @RequestBody(required = false) TransformRequest request)
            throws JsonProcessingException {
        if (request == null || request.getFileName() == null || request.getFileName().isBlank()) {
            return ResponseEntity.badRequest().body("FileName is required in the request body.");
        }

        try {
            // The referenced upload must belong to the caller — checked before
            // a single byte is read or any LLM call is spent on it. Another
            // user's (or an unknown/pre-V20) stored name is a 404.
            uploadOwnership.require(request.getFileName());

            // ws23: a named template skips the AI template detection, so the
            // transform also works with code-based extraction alone.
            int templateId = request.getTemplateId() != null
                    ? request.getTemplateId() : transformService.getTemplateId(request);
            // The transform target template must belong to the caller.
            int owner = ownership.require(templateId);

            List<String> fieldNames = templateFieldsRepository.listByTemplateId(templateId).stream()
                    .map(tf -> tf.getFieldName() == null ? "" : tf.getFieldName())
                    .toList();
            if (fieldNames.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("No template fields found for TemplateId: " + templateId);
            }

            String model = MAPPER.writeValueAsString(fieldNames);

            TransformResult resultDto = transformService.transform(request, model, templateId);

            ImportHistory importHistory = new ImportHistory();
            importHistory.setOwnerUserId(owner);
            importHistory.setBankId(ownership.requireBankId());
            importHistory.setTemplateId(templateId);
            importHistory.setFileName(request.getFileName());
            importHistory.setImportDate(LocalDateTime.now(ZoneOffset.UTC));
            ImportHistory saved = importHistoryRepository.add(importHistory);

            ImportDetails details = new ImportDetails();
            details.setImportHistoryId(saved.getImportHistoryId());
            details.setBankId(saved.getBankId());
            details.setRawJson(resultDto.getJsonPayload());
            details.setProcessStepId(1);
            importDetailsRepository.add(details);

            return ResponseEntity.ok(resultDto);
        } catch (NoSuchElementException notFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound.getMessage());
        } catch (UnsupportedOperationException unsupported) {
            return ResponseEntity.badRequest().body(unsupported.getMessage());
        } catch (AiNotConfiguredException unavailable) {
            // No key, circuit open or AI off for the bank: AiExceptionHandler's 503.
            throw unavailable;
        } catch (Exception ex) {
            // The message of an arbitrary failure can quote SQL, paths or the
            // file's content — log it, answer generically (requestId is in the
            // X-Request-Id response header for correlation).
            log.error("Transform failed", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred.");
        }
    }
}
