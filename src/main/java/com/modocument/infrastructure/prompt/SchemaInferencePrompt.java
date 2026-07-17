package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.PromptInput;
import com.modocument.application.dto.SchemaInferencePromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Suggests a template schema (field names, data types, examples) from a
 * sample of extracted records, guided by what the user said they want.
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
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data schema analyst.").append(System.lineSeparator());
        prompt.append("From the sample records below, infer the most useful flat table schema: ")
                .append("one entry per field with name (letters/digits/underscores), ")
                .append("dataType (one of: string, int, decimal, date, datetime, boolean) ")
                .append("and a representative example value taken from the samples.")
                .append(System.lineSeparator());
        prompt.append("Only include fields that actually occur in the samples; never invent data.")
                .append(System.lineSeparator());
        if (schemaInput.getDataSpec() != null && !schemaInput.getDataSpec().isBlank()) {
            prompt.append("The user wants: ").append(schemaInput.getDataSpec())
                    .append(System.lineSeparator());
        }
        prompt.append("Sample records (JSON):").append(System.lineSeparator());
        prompt.append(schemaInput.getSampleRecordsJson());
        return prompt.toString();
    }
}
