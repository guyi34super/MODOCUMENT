package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.PromptInput;
import com.modocument.application.dto.SchemaInferencePromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Suggests a template schema from extracted record samples. Prompt style
 * matches {@link TableExtractionPrompt}: short steps and explicit JSON schema
 * for local models (e.g. Mistral via Ollama).
 */
@Component
public class SchemaInferencePrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "SchemaInference";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof SchemaInferencePromptInput schemaInput)) {
            throw new IllegalArgumentException("Invalid input type for SchemaInferencePrompt");
        }
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        prompt.append("TASK: Infer a flat table schema from the sample records below.").append(nl);
        prompt.append(nl).append("STEPS:").append(nl);
        prompt.append("1. List every field that appears in the samples.").append(nl);
        prompt.append("2. For each field set name (letters/digits/underscores), dataType, and example.").append(nl);
        prompt.append("3. dataType must be one of: string, int, decimal, date, datetime, boolean.").append(nl);
        prompt.append("4. example must be a real value from the samples.").append(nl);
        prompt.append("5. Return ONE JSON object only (no markdown, no explanation).").append(nl);
        prompt.append(nl);
        prompt.append("JSON RULES: double-quoted keys and strings; no trailing commas.").append(nl);
        prompt.append("Schema: {\"fields\":[{\"name\":\"FieldName\",\"dataType\":\"string\",\"example\":\"val\"}]}")
                .append(nl);
        if (schemaInput.getDataSpec() != null && !schemaInput.getDataSpec().isBlank()) {
            prompt.append(nl).append("USER GOAL: ").append(schemaInput.getDataSpec()).append(nl);
        }
        prompt.append(nl).append("SAMPLE RECORDS (JSON):").append(nl);
        prompt.append(schemaInput.getSampleRecordsJson());
        return prompt.toString();
    }
}
