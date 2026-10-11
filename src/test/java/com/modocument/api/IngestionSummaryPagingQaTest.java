package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.IngestionJobJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA, optimization round, test plan P1/P2 (contracts §5 + §10): the
 * ingestion summary page walks the caller's jobs newest first with no
 * overlap and no gap, {@code total} counts only the caller's own jobs,
 * another user's jobs (same bank or another bank) never appear, a page
 * past the end is empty, out-of-range paging is clamped (size 1..200,
 * page >= 0), and malformed input is refused. (Statement counts and the summary fields are
 * {@code ReadPathQueryCountTest}'s.)
 */
class IngestionSummaryPagingQaTest extends DetectionGateTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private IngestionJobJpaRepository jobs;

    private AppUser owner;
    private final List<Integer> ownJobsNewestFirst = new ArrayList<>();
    private final List<Integer> foreignJobs = new ArrayList<>();

    private IngestionJob job(AppUser user, LocalDateTime created) {
        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(user.getAppUserId());
        job.setBankId(user.getBankId());
        job.setFileName("qa-page-" + UUID.randomUUID() + ".csv");
        job.setCurrentStep(JobStep.UPLOADED);
        job.setStatus(JobStatus.PENDING);
        job.setCreatedDate(created);
        job.setUpdatedDate(created);
        return jobs.save(job);
    }

    @BeforeEach
    void seed() {
        Bank bank = newBank("PGQ");
        owner = newUser(bank, "pgq-owner", Role.READ_WRITE, null);
        AppUser colleague = newUser(bank, "pgq-colleague", Role.READ_WRITE, null);
        AppUser stranger = newUser(newBank("PGX"), "pgq-stranger", Role.READ_WRITE, null);
        LocalDateTime start = LocalDateTime.now().minusHours(2);
        List<Integer> oldestFirst = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            oldestFirst.add(job(owner, start.plusSeconds(i * 10L)).getIngestionJobId());
            // Interleaved, newer than the owner's: they would lead every page if they leaked.
            if (i % 5 == 0) {
                foreignJobs.add(job(colleague, start.plusSeconds(i * 10L + 5)).getIngestionJobId());
                foreignJobs.add(job(stranger, start.plusSeconds(i * 10L + 6)).getIngestionJobId());
            }
        }
        for (int i = oldestFirst.size() - 1; i >= 0; i--) {
            ownJobsNewestFirst.add(oldestFirst.get(i));
        }
    }

    private JsonNode page(String query) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/ingestion/jobs?view=summary&" + query)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void pagesWalkTheCallersJobsNewestFirstWithNoOverlapOrGap() throws Exception {
        List<Integer> walked = new ArrayList<>();
        for (int p = 0; p < 5; p++) {
            JsonNode body = page("page=" + p + "&size=5");
            assertThat(body.get("page").asInt()).isEqualTo(p);
            assertThat(body.get("size").asInt()).isEqualTo(5);
            assertThat(body.get("total").asLong()).as("total = the caller's jobs only").isEqualTo(23);
            assertThat(body.get("items").size()).isEqualTo(p < 4 ? 5 : 3);
            body.get("items").forEach(item -> walked.add(item.get("ingestionJobId").asInt()));
        }
        assertThat(walked).as("newest first, every job exactly once")
                .containsExactlyElementsOf(ownJobsNewestFirst);
        assertThat(walked).doesNotContainAnyElementsOf(foreignJobs);

        JsonNode past = page("page=9&size=5");
        assertThat(past.get("items")).isEmpty();
        assertThat(past.get("total").asLong()).isEqualTo(23);

        JsonNode clamped = page("page=0&size=1000");
        assertThat(clamped.get("size").asInt()).isEqualTo(200);
        assertThat(clamped.get("items")).hasSize(23);
    }

    @Test
    void outOfRangePagingIsClampedAndMalformedInputIsRefused() throws Exception {
        // Contract §5: size <= 200; PagedList clamps instead of refusing.
        JsonNode negative = page("page=-1&size=0");
        assertThat(negative.get("page").asInt()).isZero();
        assertThat(negative.get("size").asInt()).isEqualTo(1);
        assertThat(negative.get("items").get(0).get("ingestionJobId").asInt())
                .isEqualTo(ownJobsNewestFirst.get(0));
        for (String query : List.of("view=summary&page=x&size=5", "view=full&page=0&size=5")) {
            int status = mvc.perform(get("/api/ingestion/jobs?" + query)
                            .header("Authorization", bearer(owner)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(query).isEqualTo(400);
        }
    }
}
