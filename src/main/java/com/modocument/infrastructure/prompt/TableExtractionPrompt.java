package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.PromptInput;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

/**
 * The single extraction prompt for every document flow — spreadsheets (serialized
 * as a row-numbered grid), OCR/pdf text, Word and plain text — normalized into the
 * one {@code TableData} shape the UI understands. The caller enforces the JSON
 * contract via structured output, so the output is identical regardless of source
 * format. The guidance below is written to survive the messy realities those flows
 * produce (title banners, multi-row/merged headers, page furniture).
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
        String nl = System.lineSeparator();
        prompt.append("You are a data extraction engine. Extract the single main table from the ")
                .append("document below and return its column names and every data row.").append(nl);
        prompt.append("Rules:").append(nl);
        prompt.append("- Find the real header row. Ignore title banners, blank leading rows, ")
                .append("page headers/footers and any notes above or below the table.").append(nl);
        prompt.append("- If the header spans multiple rows, flatten it into one clear name per ")
                .append("column. Use the document's own labels where possible.").append(nl);
        prompt.append("- A value repeated across a merged/spanned region applies to every ")
                .append("row or column it covers; carry it down or across accordingly.").append(nl);
        prompt.append("- Capture every data row. Keep the original column order. ")
                .append("All cell values must be strings; use \"\" for missing values.").append(nl);
        prompt.append("- Do not invent, merge, drop, or reorder data that is not present.").append(nl);
        prompt.append("(Spreadsheet input is given as \"Row N: <tab-separated cells>\"; ")
                .append("the row numbers are context only and must not become a column.)").append(nl);
        prompt.append("Document:").append(nl);
        prompt.append(tableInput.getDocumentText());
        return prompt.toString();
    }
}
