package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.AutoMappingPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

@Component
public class AutoMappingPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "AutoMapping";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof AutoMappingPromptInput autoMapperInput)) {
            throw new IllegalArgumentException("Invalid input type for AutoMappingPrompt");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an Auto mapper classifier.").append(System.lineSeparator());
        prompt.append("I have these CSV columns:").append(System.lineSeparator());
        prompt.append(autoMapperInput.getExtractedFields()).append(System.lineSeparator());
        prompt.append("And these schema fields:").append(System.lineSeparator());
        prompt.append(autoMapperInput.getSchemaFields()).append(System.lineSeparator());
        prompt.append("Please return a JSON object mapping each CSV column to the best matching schema field, or null if no good match")
                .append(System.lineSeparator());
        return prompt.toString();
    }
}
