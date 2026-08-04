package com.modocument.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RowLineTableMaterializerTest {

    private final RowLineTableMaterializer materializer = new RowLineTableMaterializer();

    @Test
    void detectsRowLineText() {
        String text = """
                Row 1: Emp ID\tName
                Row 2: E001\tAda
                """;
        assertThat(materializer.isRowLineText(text)).isTrue();
    }

    @Test
    void parsesDataRowsUsingFixedColumns() {
        List<String> columns = List.of("Emp ID", "Name");
        String text = """
                Row 1: Emp ID\tName
                Row 2: E001\tAda
                Row 3: E002\tBob
                """;
        assertThat(materializer.parseRows(text, columns))
                .containsExactly(
                        Map.of("Emp ID", "E001", "Name", "Ada"),
                        Map.of("Emp ID", "E002", "Name", "Bob"));
    }
}
