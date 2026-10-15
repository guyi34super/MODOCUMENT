package com.modocument.application.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A job's mapped rows split by the validation outcome — the one rule for
 * "valid rows" shared by the integration API's results (IntegrationApiUseCase)
 * and the record index (TemplateRecordIndexer): a row is valid when the job
 * reached VALIDATED (or EXPORTED) and no failure in ValidationResultJson
 * names it. Rows are 1-based, as in the validation results.
 */
public final class ValidatedRows {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ValidatedRows() {
    }

    /**
     * @param table          the mapped table (null when the job has none)
     * @param validated      the job reached VALIDATED/EXPORTED and has a mapped table
     * @param valid          the valid rows, in table order
     * @param validRowIndexes the 1-based row numbers of {@code valid}, index-aligned
     * @param rejectedRows   the rejected rows by 1-based row number
     * @param errorsByRow    the failures per rejected row ({@code field}, {@code message}, {@code rule})
     */
    public record Split(TableData table, boolean validated, List<Map<String, String>> valid,
                        List<Integer> validRowIndexes,
                        TreeMap<Integer, Map<String, String>> rejectedRows,
                        Map<Integer, List<Map<String, Object>>> errorsByRow) {
    }

    /** The mapped rows of {@code job} split by the validation outcome. */
    public static Split of(IngestionJob job) {
        TableData table = read(job.getMappedRowsJson(), new TypeReference<TableData>() {
        });
        boolean validated = job.getCurrentStep() != null
                && job.getCurrentStep().ordinal() >= JobStep.VALIDATED.ordinal()
                && table != null;
        if (!validated) {
            return new Split(table, false, List.of(), List.of(), new TreeMap<>(), Map.of());
        }
        List<RowValidationResult> results = read(job.getValidationResultJson(),
                new TypeReference<List<RowValidationResult>>() {
                });
        Map<Integer, List<Map<String, Object>>> errorsByRow = new TreeMap<>();
        if (results != null) {
            for (RowValidationResult result : results) {
                if (result.errorMessage() == null) {
                    continue;
                }
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("field", result.fieldName());
                error.put("message", result.errorMessage());
                error.put("rule", result.ruleType());
                errorsByRow.computeIfAbsent(result.row(), row -> new ArrayList<>()).add(error);
            }
        }
        Set<Integer> rejected = new TreeSet<>(errorsByRow.keySet());
        List<Map<String, String>> valid = new ArrayList<>();
        List<Integer> validIndexes = new ArrayList<>();
        TreeMap<Integer, Map<String, String>> rejectedRows = new TreeMap<>();
        List<Map<String, String>> rows = table.getRows() == null ? List.of() : table.getRows();
        for (int i = 0; i < rows.size(); i++) {
            if (rejected.contains(i + 1)) {
                rejectedRows.put(i + 1, rows.get(i));
            } else {
                valid.add(rows.get(i));
                validIndexes.add(i + 1);
            }
        }
        return new Split(table, true, valid, validIndexes, rejectedRows, errorsByRow);
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (IOException e) {
            return null;
        }
    }
}
