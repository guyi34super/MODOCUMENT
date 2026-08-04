package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.ChatEditPatchPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import com.modocument.application.usecase.ChatEditScope;
import org.springframework.stereotype.Component;

/**
 * Patch-mode table editing with scope-specific payloads: only the cell, row,
 * column, or error rows implied by the user's instruction are sent to the LLM.
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

        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data table editor working in patch mode.").append(nl);
        prompt.append("The full table has these columns (JSON): ")
                .append(chatInput.getColumnsJson()).append(nl);
        appendScopeIntro(prompt, chatInput.getScope(), nl);
        prompt.append("Apply the user's instruction to fix the data below.").append(nl);
        prompt.append("Return ONLY the rows you actually modified, as JSON: ")
                .append("{\"rows\":[{\"row\":<index>,\"values\":{\"<column>\":\"<new value>\"}}]}.")
                .append(nl);
        prompt.append("In \"values\" include only the columns you changed. ")
                .append("Keep every value a string; use \"\" for empty cells.")
                .append(nl);
        prompt.append("Output strict JSON only: use double quotes for every key and value ")
                .append("(escape any inner double quote as \\\", e.g. write \"O'Brien\" and ")
                .append("\"6\\\"\"), quote every value (write \"0.5\", not .5), ")
                .append("no comments, and no \"...\" or other placeholders.")
                .append(nl);
        prompt.append("Never change a \"row\" index, never add or remove rows, ")
                .append("never invent columns that are not in the column list.")
                .append(nl);
        prompt.append("Do not include any cell you did not change. Never touch rows or columns ")
                .append("outside the data shown above; edits to anything else will be discarded.")
                .append(nl);
        prompt.append("Instruction: ").append(chatInput.getInstruction()).append(nl);
        prompt.append(payloadLabel(chatInput.getScope())).append(nl);
        prompt.append(chatInput.getPayloadJson());
        return prompt.toString();
    }

    private static void appendScopeIntro(StringBuilder prompt, ChatEditScope scope, String nl) {
        switch (scope) {
            case CELL -> prompt.append("Below is ONE cell that failed validation (row, column, value, error).")
                    .append(nl);
            case ROW -> prompt.append("Below is ONE row that failed validation (1-based row index, values, errors).")
                    .append(nl);
            case COLUMN -> prompt.append("Below are error cells in ONE column only (column name + failing cells).")
                    .append(nl);
            case ALL_ERRORS -> prompt.append("Below are ONLY the rows that failed validation. Each entry has its ")
                    .append("1-based \"row\" index in the full table, its current \"values\", and its ")
                    .append("validation \"errors\" (field name -> error message).")
                    .append(nl);
        }
    }

    private static String payloadLabel(ChatEditScope scope) {
        return switch (scope) {
            case CELL -> "Cell to fix (JSON):";
            case ROW -> "Row to fix (JSON):";
            case COLUMN -> "Column error cells (JSON):";
            case ALL_ERRORS -> "Rows to fix (JSON):";
        };
    }
}
