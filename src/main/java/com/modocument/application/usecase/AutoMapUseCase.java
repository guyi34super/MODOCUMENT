package com.modocument.application.usecase;

import com.modocument.application.dto.AutoMappingPromptInput;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.PromptStrategy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps source columns to template field names: exact name match first, then a
 * normalized match (case/punctuation-insensitive), and only the leftovers go
 * to the LLM automapper. Columns with no good match map to null.
 */
@Service
public class AutoMapUseCase {

    /** Marker for prompt lookup; injected to avoid depending on the infrastructure factory. */
    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;

    public AutoMapUseCase(List<PromptStrategy> promptStrategies, AiFilesService aiFilesService) {
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
    }

    public Map<String, String> map(List<String> sourceColumns, List<String> templateFields) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, String> normalizedFields = new LinkedHashMap<>();
        for (String field : templateFields) {
            normalizedFields.putIfAbsent(normalize(field), field);
        }

        List<String> unresolved = new ArrayList<>();
        for (String column : sourceColumns) {
            if (templateFields.contains(column)) {
                mapping.put(column, column);
                continue;
            }
            String byNormalizedName = normalizedFields.get(normalize(column));
            if (byNormalizedName != null) {
                mapping.put(column, byNormalizedName);
            } else {
                unresolved.add(column);
            }
        }

        if (!unresolved.isEmpty()) {
            mapping.putAll(mapWithLlm(unresolved, templateFields));
        }
        return mapping;
    }

    private Map<String, String> mapWithLlm(List<String> columns, List<String> templateFields) {
        PromptStrategy strategy = promptStrategies.stream()
                .filter(s -> s.getType().equals("AutoMapping"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("AutoMapping prompt not registered."));

        String prompt = strategy.buildPrompt(new AutoMappingPromptInput(
                String.join(", ", columns), String.join(", ", templateFields)));

        Map<String, String> llmMapping = aiFilesService.sendForObject(
                prompt, new ParameterizedTypeReference<Map<String, String>>() {
                });

        // Trust only mappings onto real template fields; everything else is unmapped.
        Map<String, String> result = new LinkedHashMap<>();
        for (String column : columns) {
            String target = llmMapping == null ? null : llmMapping.get(column);
            result.put(column, target != null && templateFields.contains(target) ? target : null);
        }
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
