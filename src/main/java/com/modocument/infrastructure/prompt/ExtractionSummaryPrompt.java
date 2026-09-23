package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.ExtractionSummaryPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Produces a short human-readable summary of an extraction run. Prompt style
 * matches {@link TableExtractionPrompt}: short numbered steps for local models
 * (e.g. Mistral via Ollama).
 */
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
        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        prompt.append("TASK: Summarize this data extraction run.").append(nl);
        prompt.append(nl).append("STEPS:").append(nl);
        prompt.append("1. Read the run statistics and sample records below.").append(nl);
        prompt.append("2. Write one short paragraph: what was collected, notable patterns, gaps.").append(nl);
        prompt.append("3. Add at most five bullet points for key findings.").append(nl);
        prompt.append("4. Base every statement strictly on the data — do not invent fields or counts.").append(nl);
        if (summaryInput.getDataSpec() != null && !summaryInput.getDataSpec().isBlank()) {
            prompt.append(nl).append("USER GOAL: ").append(summaryInput.getDataSpec()).append(nl);
        }
        prompt.append(nl).append("RUN STATISTICS (JSON):").append(nl);
        prompt.append(summaryInput.getStatsJson()).append(nl);
        prompt.append(nl).append("SAMPLE RECORDS (JSON):").append(nl);
        prompt.append(summaryInput.getSampleRecordsJson());
        return prompt.toString();
    }
}
