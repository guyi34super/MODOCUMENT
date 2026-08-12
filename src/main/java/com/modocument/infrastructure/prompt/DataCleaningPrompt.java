package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.DataCleaningPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Cleans a batch of extracted rows. Prompt style matches
 * {@link TableExtractionPrompt}: short steps and explicit JSON schema for local
 * models (e.g. Mistral via Ollama).
 */
@Component
public class DataCleaningPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "DataCleaning";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof DataCleaningPromptInput cleaningInput)) {
            throw new IllegalArgumentException("Invalid input type for DataCleaningPrompt");
        }
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        prompt.append("TASK: Clean the table below and return the FULL table.").append(nl);
        prompt.append(nl).append("STEPS:").append(nl);
        prompt.append("1. Keep the same columns, same row count, same row order.").append(nl);
        prompt.append("2. Format dates as ISO 8601 (yyyy-MM-dd).").append(nl);
        prompt.append("3. Strip currency symbols and thousands separators from numbers.").append(nl);
        prompt.append("4. Collapse repeated whitespace; fix obvious encoding artifacts.").append(nl);
        prompt.append("5. Use \"\" for missing values. Never invent, merge, or drop rows.").append(nl);
        prompt.append("6. Return ONE JSON object only (no markdown, no explanation).").append(nl);
        prompt.append(nl);
        prompt.append("JSON RULES: double-quoted keys and strings; all cell values are strings.").append(nl);
        prompt.append("Schema: {\"columns\":[\"Col1\"],\"rows\":[{\"Col1\":\"val\"}]}").append(nl);
        if (cleaningInput.getDataSpec() != null && !cleaningInput.getDataSpec().isBlank()) {
            prompt.append(nl).append("USER GOAL: ").append(cleaningInput.getDataSpec()).append(nl);
        }
        prompt.append(nl).append("TABLE (JSON):").append(nl);
        prompt.append(cleaningInput.getTableJson());
        return prompt.toString();
    }
}
