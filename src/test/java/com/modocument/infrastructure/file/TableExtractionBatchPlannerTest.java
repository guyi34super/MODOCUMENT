package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableExtractionBatchPlannerTest {

    private final ParsingProperties properties = new ParsingProperties();
    private final TableExtractionBatchPlanner planner = new TableExtractionBatchPlanner();

    @Test
    void singlePassForSmallMarkdownTable() {
        properties.setLlmSinglePassMaxRows(25);
        String text = """
                | Name | City |
                | --- | --- |
                | Ada | London |
                | Grace | Paris |
                """;
        TableExtractionHints hints = new TableExtractionHints(".xlsx", "docling", 2, java.util.List.of("Name", "City"), 2);

        TableExtractionPlan plan = planner.plan(text, hints, properties);

        assertThat(plan.singlePass()).isTrue();
        assertThat(plan.fullDocumentText()).isEqualTo(text);
    }

    @Test
    void batchesLargeMarkdownTable() {
        properties.setLlmBatchSize(2);
        properties.setLlmSinglePassMaxRows(2);
        StringBuilder sb = new StringBuilder("| A | B |\n| --- | --- |\n");
        for (int i = 1; i <= 5; i++) {
            sb.append("| r").append(i).append("a | r").append(i).append("b |\n");
        }
        String text = sb.toString();
        TableExtractionHints hints = new TableExtractionHints(".csv", "docling", 2, java.util.List.of("A", "B"), 5);

        TableExtractionPlan plan = planner.plan(text, hints, properties);

        assertThat(plan.singlePass()).isFalse();
        assertThat(plan.batches()).hasSize(3);
        assertThat(plan.estimatedDataRows()).isEqualTo(5);
        assertThat(plan.headerText()).contains("| A | B |");
    }

    @Test
    void firstBatchIncludesHeaderText() {
        properties.setLlmBatchSize(2);
        properties.setLlmSinglePassMaxRows(2);
        String text = """
                Row 1: Name\tCity
                Row 2: Ada\tLondon
                Row 3: Grace\tParis
                Row 4: Lin\tOslo
                """;
        TableExtractionHints hints = new TableExtractionHints(".xlsx", "local", 2, List.of("Name", "City"), 3);

        TableExtractionPlan plan = planner.plan(text, hints, properties);

        assertThat(plan.singlePass()).isFalse();
        assertThat(plan.batches()).hasSize(2);
        assertThat(plan.batches().get(0).rowText()).startsWith("Row 1: Name\tCity");
    }

    @Test
    void batchesRowLineSpreadsheetText() {
        properties.setLlmBatchSize(2);
        properties.setLlmSinglePassMaxRows(2);
        String text = """
                Row 1: Name\tCity
                Row 2: Ada\tLondon
                Row 3: Grace\tParis
                Row 4: Lin\tOslo
                """;
        TableExtractionHints hints = new TableExtractionHints(".xlsx", "local", 2, java.util.List.of("Name", "City"), 3);

        TableExtractionPlan plan = planner.plan(text, hints, properties);

        assertThat(plan.singlePass()).isFalse();
        assertThat(plan.batches()).hasSize(2);
        assertThat(plan.sampleRows()).hasSize(3);
    }

    @Test
    void narrowsBatchSizeForWideTables() {
        assertThat(TableExtractionBatchPlanner.effectiveBatchSize(50, 10)).isEqualTo(35);
        assertThat(TableExtractionBatchPlanner.effectiveBatchSize(50, 3)).isEqualTo(50);
    }

    @Test
    void singlePassForNonTabularText() {
        properties.setLlmSinglePassMaxRows(25);
        String text = "This is a plain memo with no table structure.";
        TableExtractionPlan plan = planner.plan(text, TableExtractionHints.empty(".txt", "local"), properties);

        assertThat(plan.singlePass()).isTrue();
        assertThat(plan.batches()).isEmpty();
    }
}
