package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.ChatEditPatchPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Patch-mode table editing: the prompt carries only the rows that failed
 * validation (with their error messages) and asks for a patch of modified
 * rows keyed by row index — a fraction of the tokens of the full-table edit.
 */
@Component
public class ChatEditPatchPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "ChatEditPatch";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof ChatEditPatchPromptInput chatInput)) {
            throw new IllegalArgumentException("Invalid input type for ChatEditPatchPrompt");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data table editor working in patch mode.").append(System.lineSeparator());
        prompt.append("The full table has these columns (JSON): ")
                .append(chatInput.getColumnsJson()).append(System.lineSeparator());
        prompt.append("Below are ONLY the rows that failed validation. Each entry has its ")
                .append("1-based \"row\" index in the full table, its current \"values\", and its ")
                .append("validation \"errors\" (field name -> error message).")
                .append(System.lineSeparator());
        prompt.append("Apply the user's instruction to fix these rows.").append(System.lineSeparator());
        prompt.append("Return ONLY the rows you actually modified, as JSON: ")
                .append("{\"rows\":[{\"row\":<index>,\"values\":{\"<column>\":\"<new value>\"}}]}.")
                .append(System.lineSeparator());
        prompt.append("In \"values\" include only the columns you changed. ")
                .append("Keep every value a string; use \"\" for empty cells.")
                .append(System.lineSeparator());
        prompt.append("Never change a \"row\" index, never add or remove rows, ")
                .append("never invent columns that are not in the column list.")
                .append(System.lineSeparator());
        prompt.append("Instruction: ").append(chatInput.getInstruction()).append(System.lineSeparator());
        prompt.append("Rows to fix (JSON):").append(System.lineSeparator());
        prompt.append(chatInput.getErrorRowsJson());
        return prompt.toString();
    }
}
