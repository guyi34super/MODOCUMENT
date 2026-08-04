package com.modocument.infrastructure.file;

import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.dto.TableExtractionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredTableMaterializerBenchmarkTest {

    private final StructuredTableMaterializer materializer = new StructuredTableMaterializer(
            new MarkdownTableMaterializer(),
            new RowLineTableMaterializer(),
            new TabularNormalizer(),
            new ParsingProperties());

    @TempDir
    Path tempDir;

    @Test
    void hundredRowCsvMaterializesInUnderTwoSeconds() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("Emp ID,Full Name,Department,Role,Location,Start Date,Status,Manager,Cost Center,Notes");
        for (int i = 1; i <= 100; i++) {
            lines.add(String.format(
                    "E%03d,Person %d,Engineering,Analyst,Remote,2024-01-01,Active,M001,CC100,Note %d",
                    i, i, i));
        }
        Path file = tempDir.resolve("NovaDynamics_100Rows.csv");
        Files.write(file, lines);

        List<String> headers = List.of(
                "Emp ID", "Full Name", "Department", "Role", "Location",
                "Start Date", "Status", "Manager", "Cost Center", "Notes");
        TableExtractionHints hints = new TableExtractionHints(
                ".csv", "local", headers.size(), headers, 100);
        TableExtractionPlan plan = TableExtractionPlan.singlePass("", 100, headers.size());

        long start = System.nanoTime();
        var result = materializer.tryMaterialize(file, "", hints, plan);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isPresent();
        assertThat(result.get().getRows()).hasSize(100);
        assertThat(elapsedMs).isLessThan(2000L);
    }
}
