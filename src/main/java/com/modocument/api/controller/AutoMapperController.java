package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AutoMappingPromptInput;
import com.modocument.domain.model.Permission;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.infrastructure.prompt.PromptFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequiresPermission(Permission.AI_USE)
public class AutoMapperController {

    /** Upper bound on the column list a caller may hand to the prompt. */
    static final int MAX_EXTRACTED_FIELDS_LENGTH = 4000;

    private final ImportHistoryRepository importHistoryRepository;
    private final TemplateFieldsRepository templateFieldsRepository;
    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;
    private final com.modocument.application.port.CurrentUserProvider currentUser;

    public AutoMapperController(ImportHistoryRepository importHistoryRepository,
                                TemplateFieldsRepository templateFieldsRepository,
                                PromptFactory promptFactory,
                                AiFilesService aiFilesService,
                                com.modocument.application.port.CurrentUserProvider currentUser) {
        this.importHistoryRepository = importHistoryRepository;
        this.templateFieldsRepository = templateFieldsRepository;
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/automapper/generate")
    public ResponseEntity<?> autoMap(@RequestParam(value = "extractedFields", required = false) String extractedFields,
                                     @RequestParam(value = "filename", required = false) String filename) {
        if (extractedFields == null || extractedFields.isBlank()
                || filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Both extractedFields and filename are required."));
        }
        // The parameter is spliced into a prompt, so it is bounded here rather
        // than letting a caller send an unlimited payload to the provider.
        if (extractedFields.length() > MAX_EXTRACTED_FIELDS_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "extractedFields is longer than " + MAX_EXTRACTED_FIELDS_LENGTH
                            + " characters."));
        }

        Optional<ImportHistory> importHistory =
                importHistoryRepository.getByFileNameAndOwner(filename, currentUser.requireUserId());
        if (importHistory.isEmpty()) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No import history for file '" + filename + "'."));
        }

        List<com.modocument.domain.entity.TemplateFields> templateFieldEntities =
                templateFieldsRepository.listByTemplateId(importHistory.get().getTemplateId());
        List<String> columnNames = java.util.Arrays.stream(extractedFields.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        List<AutoMappingPromptInput.ColumnInfo> columns = columnNames.stream()
                .map(name -> new AutoMappingPromptInput.ColumnInfo(name, List.of()))
                .toList();
        List<AutoMappingPromptInput.FieldInfo> fields = templateFieldEntities.stream()
                .map(tf -> new AutoMappingPromptInput.FieldInfo(
                        tf.getFieldName(),
                        tf.getTemplateDataType() != null ? tf.getTemplateDataType().getDataType() : "Text",
                        true,
                        ""))
                .toList();

        AutoMappingPromptInput input = new AutoMappingPromptInput(columns, fields, Map.of(), 5);
        PromptStrategy strategy = promptFactory.getPrompt("AutoMapping");
        String prompt = strategy.buildPrompt(input);

        String llmResponse = aiFilesService.sendPrompt(prompt);

        return ResponseEntity.ok(Map.of("prompt", llmResponse));
    }
}
