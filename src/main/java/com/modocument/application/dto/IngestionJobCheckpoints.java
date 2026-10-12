package com.modocument.application.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.IngestionJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A job's JSON checkpoints, each parsed at most ONCE (ws28). Building one
 * job view used to parse MappedRowsJson three times (the unmapped-field
 * check, the mapping-complete gate and the DTO) and ValidationResultJson
 * twice; one instance of this memo is shared by every step of a request
 * instead, so each column is read once however many gates look at it.
 *
 * <p>Reads are lenient, like the job view always was: a blank or unreadable
 * column reads as {@code null}. Not thread-safe — one instance per request.
 */
public final class IngestionJobCheckpoints {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Every checkpoint parse in this JVM — a measuring aid for tests and benchmarks. */
    private static final AtomicLong PARSES = new AtomicLong();

    private final IngestionJob job;

    private TableData parsedTable;
    private boolean parsedTableRead;
    private Map<String, String> suggestedMapping;
    private boolean suggestedMappingRead;
    private Map<String, String> mapping;
    private boolean mappingRead;
    private TableData mappedRows;
    private boolean mappedRowsRead;
    private List<RowValidationResult> validationResults;
    private boolean validationResultsRead;
    private Map<String, Object> exportInfo;
    private boolean exportInfoRead;

    private IngestionJobCheckpoints(IngestionJob job) {
        this.job = job;
    }

    public static IngestionJobCheckpoints of(IngestionJob job) {
        return new IngestionJobCheckpoints(job);
    }

    public IngestionJob job() {
        return job;
    }

    public TableData parsedTable() {
        if (!parsedTableRead) {
            parsedTable = read(job.getParsedTableJson(), new TypeReference<TableData>() {
            });
            parsedTableRead = true;
        }
        return parsedTable;
    }

    public Map<String, String> suggestedMapping() {
        if (!suggestedMappingRead) {
            suggestedMapping = read(job.getSuggestedMappingJson(),
                    new TypeReference<Map<String, String>>() {
                    });
            suggestedMappingRead = true;
        }
        return suggestedMapping;
    }

    public Map<String, String> mapping() {
        if (!mappingRead) {
            mapping = read(job.getMappingJson(), new TypeReference<Map<String, String>>() {
            });
            mappingRead = true;
        }
        return mapping;
    }

    public TableData mappedRows() {
        if (!mappedRowsRead) {
            mappedRows = read(job.getMappedRowsJson(), new TypeReference<TableData>() {
            });
            mappedRowsRead = true;
        }
        return mappedRows;
    }

    /** The mapped table's columns (the mapped template fields); empty before mapping. */
    public List<String> mappedColumns() {
        TableData table = mappedRows();
        return table == null || table.getColumns() == null ? List.of() : table.getColumns();
    }

    public List<RowValidationResult> validationResults() {
        if (!validationResultsRead) {
            validationResults = read(job.getValidationResultJson(),
                    new TypeReference<List<RowValidationResult>>() {
                    });
            validationResultsRead = true;
        }
        return validationResults;
    }

    /** Failing validation results; null when the job holds no readable results. */
    public Long validationErrorCount() {
        List<RowValidationResult> results = validationResults();
        return results == null ? null
                : results.stream().filter(r -> r.errorMessage() != null).count();
    }

    public Map<String, Object> exportInfo() {
        if (!exportInfoRead) {
            exportInfo = read(job.getExportInfoJson(), new TypeReference<Map<String, Object>>() {
            });
            exportInfoRead = true;
        }
        return exportInfo;
    }

    /** How many checkpoint columns were parsed in this JVM so far (monotonic). */
    public static long parseCount() {
        return PARSES.get();
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        PARSES.incrementAndGet();
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
