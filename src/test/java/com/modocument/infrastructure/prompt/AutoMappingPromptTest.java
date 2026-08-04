package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.AutoMappingPromptInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutoMappingPromptTest {

    private final AutoMappingPrompt prompt = new AutoMappingPrompt();

    @Test
    void includesColumnsFieldsSamplesAndAvailability() {
        String text = prompt.buildPrompt(new AutoMappingPromptInput(
                List.of(new AutoMappingPromptInput.ColumnInfo("Start", List.of("2024-01-01"))),
                List.of(
                        new AutoMappingPromptInput.FieldInfo("FirstName", "Text", false, "required"),
                        new AutoMappingPromptInput.FieldInfo("HireDate", "Date", true, "date format")),
                Map.of("FirstName", "FirstName"),
                5));

        assertThat(text).contains("Source columns to map")
                .contains("\"Start\"")
                .contains("samples:")
                .contains("2024-01-01")
                .contains("Template fields")
                .contains("rules: date format")
                .contains("Available template fields")
                .contains("HireDate")
                .contains("Already mapped")
                .contains("map 1 source column")
                .contains("JSON");
    }
}
