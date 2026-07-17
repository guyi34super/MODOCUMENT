package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.DataCleaningPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Cleans a batch of extracted rows: normalizes dates to ISO 8601, numbers to
 * plain numeric strings, trims noise — without inventing or dropping data.
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
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data cleaning assistant.").append(System.lineSeparator());
        prompt.append("Clean the table below and return the FULL table (same columns, same row ")
                .append("count, same order).").append(System.lineSeparator());
        prompt.append("Rules: format dates as ISO 8601 (yyyy-MM-dd); strip currency symbols and ")
                .append("thousands separators from numbers; collapse repeated whitespace; fix ")
                .append("obvious encoding artifacts; use \"\" for missing values. Never invent, ")
                .append("merge, or drop rows; keep every value a string.")
                .append(System.lineSeparator());
        if (cleaningInput.getDataSpec() != null && !cleaningInput.getDataSpec().isBlank()) {
            prompt.append("The user cares about: ").append(cleaningInput.getDataSpec())
                    .append(System.lineSeparator());
        }
        prompt.append("Table (JSON):").append(System.lineSeparator());
        prompt.append(cleaningInput.getTableJson());
        return prompt.toString();
    }
}
