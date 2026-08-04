package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TableData;
import com.modocument.application.dto.TableExtractionHints;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableColumnAlignerTest {

    @Test
    void realignsColumnsAndRowKeysToHints() {
        TableData llm = new TableData(
                List.of("ID", "Name"),
                List.of(Map.of("ID", "E1", "Name", "Ada")));
        TableExtractionHints hints = new TableExtractionHints(
                ".xlsx", "local", 2, List.of("Emp ID", "Full Name"), 1);

        TableData aligned = TableColumnAligner.alignToHints(llm, hints);

        assertThat(aligned.getColumns()).containsExactly("Emp ID", "Full Name");
        assertThat(aligned.getRows()).containsExactly(Map.of("Emp ID", "E1", "Full Name", "Ada"));
    }

    @Test
    void realignsMappingKeysWhenColumnsAreCorrected() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("ID", "EmployeeNumber");
        mapping.put("Name", "FullName");

        Map<String, String> aligned = TableColumnAligner.alignMappingKeys(
                mapping,
                List.of("ID", "Name"),
                List.of("Emp ID", "Full Name"));

        assertThat(aligned)
                .containsEntry("Emp ID", "EmployeeNumber")
                .containsEntry("Full Name", "FullName");
    }
}
