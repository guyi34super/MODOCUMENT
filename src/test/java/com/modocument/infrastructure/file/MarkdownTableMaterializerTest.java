package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTableMaterializerTest {

    private final MarkdownTableMaterializer materializer = new MarkdownTableMaterializer();

    @Test
    void parsesPipeTableRows() {
        String batch = """
                | E001 | Amelia Smith | Engineering |
                | E002 | Liam Johnson | Marketing |
                """;
        List<String> columns = List.of("Emp ID", "Full Name", "Department");

        List<Map<String, String>> rows = materializer.parseRows(batch, columns);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("Emp ID", "E001");
        assertThat(rows.get(0)).containsEntry("Full Name", "Amelia Smith");
    }

    @Test
    void detectsMarkdownTableText() {
        assertThat(materializer.isMarkdownTableText("| A | B |")).isTrue();
        assertThat(materializer.isMarkdownTableText("Row 1: A\tB")).isFalse();
    }
}
