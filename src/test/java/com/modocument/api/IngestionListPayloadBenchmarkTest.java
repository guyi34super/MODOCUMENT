package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.IngestionJobCheckpoints;
import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws28 measurement over HTTP and H2 (50 jobs; the 1,000-row variant is
 * IngestionJobSummaryBenchmarkTest): the job list before
 * (the unpaged full views, still served without {@code view=summary}) and
 * after (the paged summary), in response bytes, SQL statements and time;
 * and the single-job view's checkpoint parses before and after the
 * memoized {@link IngestionJobCheckpoints}. The numbers are printed for the
 * QA report; the assertions pin the direction and the order of magnitude.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionListPayloadBenchmarkTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int JOBS = 50;
    /**
     * 200 rows per job here: the H2 test schema maps the checkpoint columns to
     * VARCHAR(32600). IngestionJobSummaryBenchmarkTest repeats the payload and
     * parse measurement at the full 1,000 rows without a database.
     */
    private static final int ROWS = 200;
    private static final List<String> COLUMNS = List.of("Account", "Name", "Amount", "Date", "Branch", "Ref");

    @Autowired private MockMvc mvc;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JwtService jwtService;
    @Autowired private BankJpaRepository banks;
    @Autowired private AppUserJpaRepository users;
    @Autowired private TemplateJpaRepository templates;
    @Autowired private TemplateFieldsJpaRepository fields;
    @Autowired private IngestionJobJpaRepository jobs;
    @Autowired private IngestionJobUseCase useCase;

    @Test
    void fiftyJobsOfAThousandRowsListedBeforeAndAfter() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Bank bank = banks.save(new Bank("B" + tag, "Benchmark " + tag));
        AppUser owner = new AppUser();
        owner.setEmail("ws28-bench-" + tag + "@example.com");
        owner.setPasswordHash("{noop}x");
        owner.setDisplayName("bench");
        owner.setBankId(bank.getBankId());
        owner.setRole(Role.READ_WRITE);
        owner.setSettingsJson("{}");
        owner = users.save(owner);
        Template template = new Template();
        template.setOwnerUserId(owner.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setName("bench-" + tag);
        template = templates.save(template);
        for (String column : COLUMNS) {
            TemplateFields field = new TemplateFields();
            field.setTemplateId(template.getTemplateId());
            field.setBankId(bank.getBankId());
            field.setFieldName(column);
            field.setTemplateDataTypeId(1);
            fields.save(field);
        }

        String table = JSON.writeValueAsString(table());
        String mapping = JSON.writeValueAsString(identityMapping());
        String validation = JSON.writeValueAsString(problems());
        List<IngestionJob> seeded = new ArrayList<>();
        for (int i = 0; i < JOBS; i++) {
            IngestionJob job = new IngestionJob();
            job.setOwnerUserId(owner.getAppUserId());
            job.setBankId(bank.getBankId());
            job.setTemplateId(template.getTemplateId());
            job.setFileName("statement-" + i + ".csv");
            job.setStatus(JobStatus.COMPLETED);
            job.setCurrentStep(JobStep.VALIDATED);
            job.setParsedTableJson(table);
            job.setMappingJson(mapping);
            job.setMappedRowsJson(table);
            job.setValidationResultJson(validation);
            job.setValidationErrorCount(problems().size());
            job.setCreatedDate(LocalDateTime.now().plusSeconds(i));
            job.setUpdatedDate(job.getCreatedDate());
            seeded.add(job);
        }
        jobs.saveAll(seeded);
        String auth = "Bearer " + jwtService.issueToken(owner);
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            // Warm both paths once (class loading, PII classification of the template).
            mvc.perform(get("/api/ingestion/jobs").header("Authorization", auth)).andExpect(status().isOk());
            mvc.perform(get("/api/ingestion/jobs?view=summary&page=0&size=50").header("Authorization", auth))
                    .andExpect(status().isOk());

            statistics.clear();
            long beforeStart = System.nanoTime();
            MvcResult before = mvc.perform(get("/api/ingestion/jobs").header("Authorization", auth))
                    .andExpect(status().isOk()).andReturn();
            long beforeMillis = (System.nanoTime() - beforeStart) / 1_000_000;
            long beforeStatements = statistics.getPrepareStatementCount();

            statistics.clear();
            long afterStart = System.nanoTime();
            MvcResult after = mvc.perform(get("/api/ingestion/jobs?view=summary&page=0&size=50")
                            .header("Authorization", auth))
                    .andExpect(status().isOk()).andReturn();
            long afterMillis = (System.nanoTime() - afterStart) / 1_000_000;
            long afterStatements = statistics.getPrepareStatementCount();

            int beforeBytes = before.getResponse().getContentAsByteArray().length;
            int afterBytes = after.getResponse().getContentAsByteArray().length;
            JsonNode summary = JSON.readTree(after.getResponse().getContentAsString());
            assertThat(summary.get("items")).hasSize(JOBS);
            assertThat(summary.get("items").get(0).get("unmappedFieldCount").asInt()).isZero();
            assertThat(summary.get("items").get(0).get("validationErrorCount").asInt())
                    .isEqualTo(problems().size());
            System.out.printf("ws28 job list, %d jobs x %d rows: full list %,d bytes / %d statements / %d ms;"
                            + " summary %,d bytes / %d statements / %d ms (%.0fx smaller)%n",
                    JOBS, ROWS, beforeBytes, beforeStatements, beforeMillis, afterBytes, afterStatements,
                    afterMillis, (double) beforeBytes / afterBytes);
            assertThat(afterBytes * 20L).as("summary at least 20x smaller").isLessThan(beforeBytes);

            // Single job view: parses of the JSON checkpoints, before and after.
            IngestionJob one = jobs.findByOwnerUserId(owner.getAppUserId()).get(0);
            long parsesBefore = IngestionJobCheckpoints.parseCount();
            useCase.view(one);
            long viewParses = IngestionJobCheckpoints.parseCount() - parsesBefore;
            // Pre-ws28 path: unmappedFields and the mapping-complete gate each
            // parsed MappedRowsJson, the error gate parsed ValidationResultJson,
            // then the DTO parsed every non-empty column again (here four:
            // parsed table, mapping, mapped rows, validation results).
            long legacyParses = 1 + 1 + 1 + 4;
            long legacyBytes = 3L * table.length() + 2L * validation.length() + table.length()
                    + mapping.length();
            long viewBytes = 2L * table.length() + validation.length() + mapping.length();
            System.out.printf("ws28 single job view: %d checkpoint parses (%,d JSON bytes) before, %d (%,d bytes)"
                    + " after%n", legacyParses, legacyBytes, viewParses, viewBytes);
            assertThat(viewParses).as("each JSON column parsed once").isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
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

    private static Map<String, String> identityMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        COLUMNS.forEach(column -> mapping.put(column, column));
        return mapping;
    }

    private static List<RowValidationResult> problems() {
        List<RowValidationResult> problems = new ArrayList<>();
        for (int r = 0; r < 40; r++) {
            problems.add(new RowValidationResult(r * 20, "Amount", "999999", "too large", "RANGE"));
        }
        return problems;
    }
}
