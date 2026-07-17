package com.modocument.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.AutoMappingPromptInput;
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
public class AutoMapperController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImportHistoryRepository importHistoryRepository;
    private final TemplateFieldsRepository templateFieldsRepository;
    private final PromptFactory promptFactory;
    private final AiFilesService aiFilesService;

    public AutoMapperController(ImportHistoryRepository importHistoryRepository,
                                TemplateFieldsRepository templateFieldsRepository,
                                PromptFactory promptFactory,
                                AiFilesService aiFilesService) {
        this.importHistoryRepository = importHistoryRepository;
        this.templateFieldsRepository = templateFieldsRepository;
        this.promptFactory = promptFactory;
        this.aiFilesService = aiFilesService;
    }

    @PostMapping("/api/automapper/generate")
    public ResponseEntity<?> autoMap(@RequestParam(value = "extractedFields", required = false) String extractedFields,
                                     @RequestParam(value = "filename", required = false) String filename)
            throws JsonProcessingException {
        if (extractedFields == null || extractedFields.isBlank()
                || filename == null || filename.isBlank()) {
            return ResponseEntity.badRequest().body("Both extractedFields and filename are required.");
        }

        Optional<ImportHistory> importHistory = importHistoryRepository.getByFileName(filename);
        if (importHistory.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<String> fieldNames = templateFieldsRepository.list().stream()
                .filter(tf -> tf.getTemplateId() == importHistory.get().getTemplateId())
                .map(tf -> tf.getFieldName())
                .toList();

        String schemaFields = MAPPER.writeValueAsString(fieldNames);

        AutoMappingPromptInput input = new AutoMappingPromptInput(extractedFields, schemaFields);
        PromptStrategy strategy = promptFactory.getPrompt("AutoMapping");
        String prompt = strategy.buildPrompt(input);

        String llmResponse = aiFilesService.sendPrompt(prompt);

        return ResponseEntity.ok(Map.of("prompt", llmResponse));
    }
}
