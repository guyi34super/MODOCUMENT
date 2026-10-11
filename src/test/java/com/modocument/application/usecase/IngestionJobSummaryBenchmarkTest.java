package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.modocument.application.dto.IngestionJobCheckpoints;
import com.modocument.application.dto.IngestionJobSummary;
import com.modocument.application.dto.PagedList;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ws28 measurement at the plan's size — 50 jobs × 1,000 rows — without a
 * database (the H2 test schema caps checkpoint columns at 32,600
 * characters): the legacy full-view list against the paged summary in
 * serialized bytes, and checkpoint parses per job view before and after the
 * {@link IngestionJobCheckpoints} memo. Numbers are printed for the QA report.
 */
class IngestionJobSummaryBenchmarkTest {

    private static final int OWNER = 1;
    private static final int JOBS = 50;
    private static final int ROWS = 1_000;
    private static final List<String> COLUMNS = List.of("Account", "Name", "Amount", "Date", "Branch", "Ref");
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Test
    void fiftyJobsOfAThousandRows() throws Exception {
        String table = JSON.writeValueAsString(table());
        Map<String, String> identity = new LinkedHashMap<>();
        COLUMNS.forEach(column -> identity.put(column, column));
        String mapping = JSON.writeValueAsString(identity);
        List<RowValidationResult> problems = new ArrayList<>();
        for (int r = 0; r < 40; r++) {
            problems.add(new RowValidationResult(r * 20, "Amount", "999999", "too large", "RANGE"));
        }
        String validation = JSON.writeValueAsString(problems);

        List<IngestionJob> stored = new ArrayList<>();
        for (int i = 0; i < JOBS; i++) {
            IngestionJob job = new IngestionJob();
            job.setIngestionJobId(i + 1);
            job.setOwnerUserId(OWNER);
            job.setTemplateId(7);
            job.setFileName("statement-" + i + ".csv");
            job.setStatus(JobStatus.COMPLETED);
            job.setCurrentStep(JobStep.VALIDATED);
            job.setParsedTableJson(table);
            job.setMappingJson(mapping);
            job.setMappedRowsJson(table);
            job.setValidationResultJson(validation);
            job.setValidationErrorCount(problems.size());
            job.setCreatedDate(LocalDateTime.now().plusSeconds(i));
            job.setUpdatedDate(job.getCreatedDate());
            stored.add(job);
        }
        IngestionJobRepository jobs = mock(IngestionJobRepository.class);
        when(jobs.listByOwner(OWNER)).thenReturn(stored);
        when(jobs.listSummariesByOwner(anyInt(), anyInt(), anyInt())).thenCallRealMethod();
        when(jobs.countByOwner(anyInt())).thenCallRealMethod();
        when(jobs.listNewestByOwner(anyInt(), anyInt())).thenCallRealMethod();
        TemplateFieldsRepository fields = mock(TemplateFieldsRepository.class);
        List<TemplateFields> templateFields = COLUMNS.stream().map(name -> {
            TemplateFields field = new TemplateFields();
            field.setTemplateId(7);
            field.setFieldName(name);
            return field;
        }).toList();
        when(fields.listByTemplateId(7)).thenReturn(templateFields);
        when(fields.listByTemplateIds(any())).thenCallRealMethod();
        when(fields.fieldNamesByTemplateIds(any())).thenCallRealMethod();
        IngestionJobUseCase useCase = new IngestionJobUseCase(jobs, mock(TemplateRepository.class), fields,
                mock(ImportHistoryRepository.class), mock(ImportDetailsRepository.class),
                mock(IngestionParseStep.class), mock(AutoMapUseCase.class), mock(ValidateRowsUseCase.class),
                FixedUser.provider(OWNER));

        long parsesBefore = IngestionJobCheckpoints.parseCount();
        long fullStart = System.nanoTime();
        byte[] full = JSON.writeValueAsBytes(useCase.views(useCase.listCapped()));
        long fullMillis = (System.nanoTime() - fullStart) / 1_000_000;
        long listParses = IngestionJobCheckpoints.parseCount() - parsesBefore;

        long summaryStart = System.nanoTime();
        PagedList<IngestionJobSummary> page = useCase.summaries(0, 50);
        byte[] summary = JSON.writeValueAsBytes(page);
        long summaryMillis = (System.nanoTime() - summaryStart) / 1_000_000;

        assertThat(page.items()).hasSize(JOBS);
        assertThat(page.items().get(0).unmappedFieldCount()).isZero();
        assertThat(page.items().get(0).validationErrorCount()).isEqualTo(40);
        // Each job view parses its four non-empty columns once (it was seven:
        // MappedRowsJson three times, ValidationResultJson twice).
        assertThat(listParses).isEqualTo(4L * JOBS);
        System.out.printf("ws28 job list (no DB), %d jobs x %d rows: full views %,d bytes in %d ms"
                        + " (%d checkpoint parses, %d before the memo); summary %,d bytes in %d ms"
                        + " (0 checkpoint parses), %.0fx smaller%n",
                JOBS, ROWS, full.length, fullMillis, listParses, 7L * JOBS, summary.length, summaryMillis,
                (double) full.length / summary.length);
        assertThat(summary.length * 500L).as("summary at least 500x smaller").isLessThan(full.length);
    }

    private static TableData table() {
        List<Map<String, String>> rows = new ArrayList<>(ROWS);
        for (int r = 0; r < ROWS; r++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Account", "MU" + (4000000000L + r));
            row.put("Name", "Customer number " + r);
            row.put("Amount", String.valueOf(1000 + r * 7.25));
            row.put("Date", "2026-09-" + (10 + r % 20));
            row.put("Branch", "Port Louis " + (r % 9));
            row.put("Ref", "REF-" + r);
            rows.add(row);
        }
        return new TableData(COLUMNS, rows);
    }
}
