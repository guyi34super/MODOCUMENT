package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredTableMaterializerTest {

    private final ParsingProperties properties = new ParsingProperties();
    private final StructuredTableMaterializer materializer = new StructuredTableMaterializer(
            new MarkdownTableMaterializer(),
            new RowLineTableMaterializer(),
            new TabularNormalizer(),
            properties);

    @TempDir
    Path tempDir;

    @Test
    void materializesRowLineSpreadsheetText() throws Exception {
        Path file = tempDir.resolve("book.xlsx");
        Files.writeString(file, "placeholder");
        String text = """
                Row 1: Emp ID\tFull Name
                Row 2: E001\tAda
                Row 3: E002\tBob
                """;
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "local", 2, List.of("Emp ID", "Full Name"), 2);
        TableExtractionPlan plan = new TableExtractionPlan(
                false, "Row 1: Emp ID\tFull Name", List.of(), List.of(), 2, 2, text);

        Optional<TableData> result = materializer.tryMaterialize(file, text, hints, plan);

        assertThat(result).isPresent();
        assertThat(result.get().getRows()).hasSize(2);
        assertThat(result.get().getRows().get(0)).containsEntry("Emp ID", "E001");
    }

    @Test
    void fallsBackWhenRowCountMismatch() {
        String text = """
                Row 1: A\tB
                Row 2: 1\t2
                """;
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "local", 2, List.of("A", "B"), 99);
        TableExtractionPlan plan = TableExtractionPlan.singlePass(text, 1, 2);

        assertThat(materializer.tryMaterialize(tempDir.resolve("x.xlsx"), text, hints, plan))
                .isEmpty();
    }

    @Test
    void disabledViaConfig() {
        properties.setDeterministicEnabled(false);
        String text = "Row 1: A\tB\nRow 2: 1\t2\n";
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "local", 2, List.of("A", "B"), 1);
        TableExtractionPlan plan = TableExtractionPlan.singlePass(text, 1, 2);

        assertThat(materializer.tryMaterialize(tempDir.resolve("x.xlsx"), text, hints, plan))
                .isEmpty();
    }

    @Test
    void materializesRealExcelFile() throws Exception {
        Path file = tempDir.resolve("data.csv");
        Files.writeString(file, "Name,City\nAda,London\nBob,Paris\n");
        TableExtractionHints hints = new TableExtractionHints(
                ".csv", "local", 2, List.of("Name", "City"), 2);
        TableExtractionPlan plan = TableExtractionPlan.singlePass("", 2, 2);

        Optional<TableData> result = materializer.tryMaterialize(file, "", hints, plan);

        assertThat(result).isPresent();
        assertThat(result.get().getRows()).hasSize(2);
        assertThat(result.get().getRows().get(0)).isEqualTo(Map.of("Name", "Ada", "City", "London"));
    }
}
