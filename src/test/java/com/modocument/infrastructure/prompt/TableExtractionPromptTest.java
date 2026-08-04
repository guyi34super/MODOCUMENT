package com.modocument.infrastructure.prompt;

import com.modocument.domain.entity.TemplateFields;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionMode;
import com.modocument.application.dto.TableExtractionPromptInput;
import com.modocument.application.dto.TableRowBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableExtractionPromptTest {

    private final TableExtractionPrompt prompt = new TableExtractionPrompt();

    @Test
    void carriesTheDocumentTextAndSpreadsheetAwareGuidance() {
        String built = prompt.buildPrompt(new TableExtractionPromptInput("Row 1: Name\tCity"));

        assertThat(built).contains("Row 1: Name\tCity");
        assertThat(built).contains("STEPS:");
        assertThat(built).contains("JSON");
    }

    @Test
    void injectsAuthoritativeHeadersFromHints() {
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "docling", 3, List.of("A", "B", "C"), 10);
        String built = prompt.buildPrompt(new TableExtractionPromptInput("doc text", hints));

        assertThat(built).contains("AUTHORITATIVE COLUMN NAMES");
        assertThat(built).contains("\"A\"");
        assertThat(built).contains("Expected columns: 3");
        assertThat(built).contains("Pipe-separated markdown");
    }

    @Test
    void injectsDynamicExampleFromHints() {
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "docling", 2, List.of("Emp ID", "Full Name"), 10);
        TemplateFields field = new TemplateFields();
        field.setFieldName("ID");
        String built = prompt.buildPrompt(new TableExtractionPromptInput(
                "doc text", hints, TableExtractionMode.FULL, null, null));

        assertThat(built).contains("EXAMPLE");
        assertThat(built).contains("\"Emp ID\"");
        assertThat(built).contains("\"Full Name\"");
    }

    @Test
    void deprecatedInjectedHintsAndDoclingRules() {
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "docling", 3, List.of("A", "B", "C"), 10);
        String built = prompt.buildPrompt(new TableExtractionPromptInput("doc text", hints));

        assertThat(built).doesNotContain("Candidate header cells:");
        assertThat(built).contains("AUTHORITATIVE COLUMN NAMES");
    }

    @Test
    void extractAndMapModeIncludesTemplateFieldsAndMappingContract() {
        TemplateFields field = new TemplateFields();
        field.setFieldName("EmployeeNumber");
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "local", 2, List.of("Emp ID", "Name"), 1);
        String built = prompt.buildPrompt(new TableExtractionPromptInput(
                "Row 1: Emp ID\tName", hints, TableExtractionMode.EXTRACT_AND_MAP,
                null, null, List.of(field)));

        assertThat(built).contains("\"mapping\"");
        assertThat(built).contains("EmployeeNumber");
        assertThat(built).contains("TEMPLATE FIELDS");
        assertThat(built).contains("AUTHORITATIVE COLUMN NAMES");
    }

    @Test
    void schemaModeRequestsColumnsOnly() {
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "docling", 3, List.of("A", "B", "C"), 100);
        String built = prompt.buildPrompt(new TableExtractionPromptInput(
                "header row", hints, TableExtractionMode.SCHEMA, null, null));

        assertThat(built).contains("\"columns\"");
        assertThat(built).contains("AUTHORITATIVE COLUMN NAMES");
        assertThat(built).contains("HEADER AND SAMPLE ROWS");
    }

    @Test
    void rowBatchModeFixesColumnSchemaAndBatchContext() {
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "docling", 2, List.of("Name", "City"), 100);
        TableRowBatch batch = new TableRowBatch(2, 4, 26, 50, "Row 26: Ada\tLondon");
        String built = prompt.buildPrompt(new TableExtractionPromptInput(
                batch.rowText(), hints, TableExtractionMode.ROW_BATCH,
                List.of("Name", "City"), batch));

        assertThat(built).contains("Batch: 2 of 4");
        assertThat(built).contains("rows 26–50");
    }

    @Test
    void pdfFormatRulesInFullMode() {
        TableExtractionHints hints = new TableExtractionHints(
                ".pdf", "local", 0, List.of(), 0);
        String built = prompt.buildPrompt(new TableExtractionPromptInput("page text", hints));

        assertThat(built).contains("Tables may continue across pages");
    }

    @Test
    void rejectsTheWrongInputType() {
        assertThatThrownBy(() -> prompt.buildPrompt(new com.modocument.application.dto.PromptInput() {
        })).isInstanceOf(IllegalArgumentException.class);
    }
}
