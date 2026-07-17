package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.ChatEditPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Lets the user modify an ingested table with a natural-language instruction;
 * the caller enforces the TableData JSON contract via structured output.
 */
@Component
public class ChatEditPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "ChatEdit";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof ChatEditPromptInput chatInput)) {
            throw new IllegalArgumentException("Invalid input type for ChatEditPrompt");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data table editor.").append(System.lineSeparator());
        prompt.append("Apply the user's instruction to the table below and return the FULL ")
                .append("modified table (all columns and all rows, not only the changed ones).")
                .append(System.lineSeparator());
        prompt.append("Keep every value a string; use \"\" for empty cells. ")
                .append("Do not change data the instruction does not ask you to change.")
                .append(System.lineSeparator());
        prompt.append("Instruction: ").append(chatInput.getInstruction()).append(System.lineSeparator());
        prompt.append("Current table (JSON):").append(System.lineSeparator());
        prompt.append(chatInput.getTableJson());
        return prompt.toString();
    }
}
