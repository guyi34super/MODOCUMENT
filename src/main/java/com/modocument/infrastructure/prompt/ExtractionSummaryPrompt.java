package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.ExtractionSummaryPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/** Produces a short human-readable summary of an extraction run. */
@Component
public class ExtractionSummaryPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "ExtractionSummary";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof ExtractionSummaryPromptInput summaryInput)) {
            throw new IllegalArgumentException("Invalid input type for ExtractionSummaryPrompt");
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data extraction analyst.").append(System.lineSeparator());
        prompt.append("Summarize this extraction run in a short paragraph plus at most five ")
                .append("bullet points: what was collected, notable patterns in the sample ")
                .append("records, and any gaps or quality issues suggested by the statistics. ")
                .append("Base every statement strictly on the data below.")
                .append(System.lineSeparator());
        if (summaryInput.getDataSpec() != null && !summaryInput.getDataSpec().isBlank()) {
            prompt.append("The user wanted: ").append(summaryInput.getDataSpec())
                    .append(System.lineSeparator());
        }
        prompt.append("Run statistics (JSON):").append(System.lineSeparator());
        prompt.append(summaryInput.getStatsJson()).append(System.lineSeparator());
        prompt.append("Sample records (JSON):").append(System.lineSeparator());
        prompt.append(summaryInput.getSampleRecordsJson());
        return prompt.toString();
    }
}
