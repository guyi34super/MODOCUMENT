package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TableExtractionHintsBuilderTest {

    private final TableExtractionHintsBuilder builder = new TableExtractionHintsBuilder();

    @TempDir
    Path tempDir;

    @Test
    void parsesDoclingMarkdownTableHeaders() throws Exception {
        String markdown = """
                # Report
                | Emp ID | Name | Dept |
                | --- | --- | --- |
                | E1 | Ada | Eng |
                | E2 | Bob | Sales |
                """;
        var hints = builder.build(tempDir.resolve("file.pdf"), markdown, "docling");

        assertThat(hints.sourceFormat()).isEqualTo(".pdf");
        assertThat(hints.expectedColumnCount()).isEqualTo(3);
        assertThat(hints.candidateHeaderCells()).containsExactly("Emp ID", "Name", "Dept");
        assertThat(hints.dataRowCount()).isEqualTo(2);
    }

    @Test
    void parsesCsvHeaders() throws Exception {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "First,Last,Email\nAda,Lovelace,ada@test.com\n");
        var hints = builder.build(csv, "", "local");

        assertThat(hints.expectedColumnCount()).isEqualTo(3);
        assertThat(hints.candidateHeaderCells()).containsExactly("First", "Last", "Email");
        assertThat(hints.dataRowCount()).isEqualTo(1);
    }

    @Test
    void detectsTabularBlockInPlainText() {
        String text = """
                Notes above the table
                Name\tCity
                Ada\tLondon
                Grace\tParis
                """;
        var hints = builder.build(tempDir.resolve("notes.txt"), text, "local");

        assertThat(hints.expectedColumnCount()).isEqualTo(2);
        assertThat(hints.dataRowCount()).isEqualTo(2);
    }
}
