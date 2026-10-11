package com.modocument.infrastructure.persistence;

import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.ImportHistory;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.JobStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner column and the {@code *ByOwner} queries are what actually stop one
 * user reaching another's data (the broken-access-control finding). This proves
 * the filtering works against the real schema, not just the in-memory fakes.
 *
 * <p>{@code @Transactional} rolls back each method so the rows seeded here don't
 * leak into the shared H2 instance and pollute other tests' counts.
 */
@SpringBootTest
@AutoConfigureMockMvc // same context configuration as the API tests (one cached context)
@ActiveProfiles("test")
@Transactional
class CrossUserAccessTest {

    private static final int ALICE = 101;
    private static final int BOB = 202;
    private static final int BANK = 1;
    private static final int OTHER_BANK = 999_001;

    @Autowired
    private TemplateRepository templates;
    @Autowired
    private IngestionJobRepository jobs;
    @Autowired
    private ImportHistoryRepository imports;

    @Test
    void aTemplateIsInvisibleToAnotherUser() {
        Template alices = new Template(0, "Alice's template", null);
        alices.setOwnerUserId(ALICE);
        alices.setBankId(BANK);
        templates.add(alices);
        int id = alices.getTemplateId();

        // PRIVATE (the default): Bob, same bank, neither finds nor lists it.
        assertThat(templates.getAccessible(id, ALICE, BANK)).isPresent();
        assertThat(templates.getAccessible(id, BOB, BANK)).isEmpty();
        assertThat(templates.listAccessible(BOB, BANK))
                .noneMatch(t -> t.getTemplateId() == id);
    }

    @Test
    void aBankSharedTemplateReachesTheSameBankOnly() {
        Template shared = new Template(0, "Alice's shared template", null);
        shared.setOwnerUserId(ALICE);
        shared.setBankId(BANK);
        shared.setVisibility(com.modocument.domain.model.TemplateVisibility.BANK);
        templates.add(shared);
        int id = shared.getTemplateId();

        assertThat(templates.getAccessible(id, BOB, BANK)).isPresent();
        assertThat(templates.listAccessible(BOB, BANK)).anyMatch(t -> t.getTemplateId() == id);
        // Another bank's user never sees it (on H2 this predicate is the only wall).
        assertThat(templates.getAccessible(id, BOB, OTHER_BANK)).isEmpty();
        assertThat(templates.listAccessible(BOB, OTHER_BANK))
                .noneMatch(t -> t.getTemplateId() == id);
    }

    @Test
    void anIngestionJobIsInvisibleToAnotherUser() {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(ALICE);
        job.setTemplateId(1);
        job.setFileName("alice.csv");
        job.setCurrentStep(JobStep.MAPPED);
        IngestionJob saved = jobs.save(job);

        assertThat(jobs.getByIdAndOwner(saved.getIngestionJobId(), ALICE)).isPresent();
        assertThat(jobs.getByIdAndOwner(saved.getIngestionJobId(), BOB)).isEmpty();
        assertThat(jobs.listByOwner(BOB))
                .noneMatch(j -> j.getIngestionJobId() == saved.getIngestionJobId());
    }

    @Test
    void mappingHistoryNeverCrossesUsers() {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(ALICE);
        job.setTemplateId(77);
        job.setCurrentStep(JobStep.VALIDATED);
        job.setMappingJson("{\"col\":\"Field\"}");
        jobs.save(job);

        assertThat(jobs.listRecentMappingJson(77, ALICE, 25)).containsExactly("{\"col\":\"Field\"}");
        assertThat(jobs.listRecentMappingJson(77, BOB, 25)).isEmpty();
    }

    @Test
    void anImportIsInvisibleToAnotherUser() {
        Template t = new Template(0, "t", null);
        t.setOwnerUserId(ALICE);
        templates.add(t);

        ImportHistory history = new ImportHistory();
        history.setOwnerUserId(ALICE);
        history.setTemplateId(t.getTemplateId());
        history.setFileName("alice-import.csv");
        ImportHistory saved = imports.add(history);

        assertThat(imports.getByIdAndOwner(saved.getImportHistoryId(), ALICE)).isPresent();
        assertThat(imports.getByIdAndOwner(saved.getImportHistoryId(), BOB)).isEmpty();
        assertThat(imports.getByFileNameAndOwner("alice-import.csv", BOB)).isEmpty();
        assertThat(imports.getByFileNameAndOwner("alice-import.csv", ALICE)).isPresent();
    }
}
