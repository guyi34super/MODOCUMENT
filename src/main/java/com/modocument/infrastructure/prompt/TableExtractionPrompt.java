package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.PromptInput;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * Asks the LLM to normalize unstructured document text (from docling/OCR/pdf
 * extraction) into the TableData shape; the caller enforces the JSON contract
 * via structured output.
 */
@Component
public class TableExtractionPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "TableExtraction";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof TableExtractionPromptInput tableInput)) {
            throw new IllegalArgumentException("Invalid input type for TableExtractionPrompt");
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a data extraction engine.").append(System.lineSeparator());
        prompt.append("Extract the tabular data contained in the following document text.")
                .append(System.lineSeparator());
        prompt.append("Identify the column names and every data row. ")
                .append("Use the document's own labels as column names where possible. ")
                .append("All cell values must be strings; use \"\" for missing values. ")
                .append("Do not invent data that is not present in the document.")
                .append(System.lineSeparator());
        prompt.append("Document text:").append(System.lineSeparator());
        prompt.append(tableInput.getDocumentText());
        return prompt.toString();
    }
}
