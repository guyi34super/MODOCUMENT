package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.TableExtractionPromptInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableExtractionPromptTest {

    private final TableExtractionPrompt prompt = new TableExtractionPrompt();

    @Test
    void carriesTheDocumentTextAndSpreadsheetAwareGuidance() {
        String built = prompt.buildPrompt(new TableExtractionPromptInput("Row 1: Name\tCity"));

        assertThat(built).contains("Row 1: Name\tCity");   // the document itself
        assertThat(built).contains("header");              // find the real header
        assertThat(built).contains("merged");              // fill merged/spanned cells
        assertThat(built).contains("every data row");      // capture all rows
        assertThat(built).containsIgnoringCase("do not invent");
    }

    @Test
    void rejectsTheWrongInputType() {
        assertThatThrownBy(() -> prompt.buildPrompt(new com.modocument.application.dto.PromptInput() {
        })).isInstanceOf(IllegalArgumentException.class);
    }
}
