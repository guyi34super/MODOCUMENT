package com.modocument.infrastructure.persistence;

import com.modocument.application.port.IngestionJobRepository;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two requests that load the same job and both write back the whole checkpoint
 * set (a resume run and a cell-edit save, say) must not silently overwrite each
 * other — the second writer loses on the version check.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionJobOptimisticLockTest {

    @Autowired
    private IngestionJobRepository jobs;

    private int newJob() {
        IngestionJob job = new IngestionJob();
        job.setTemplateId(1);
        job.setFileName("people.csv");
        job.setCurrentStep(JobStep.MAPPED);
        job.setMappedRowsJson("{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"ada\"}]}");
        return jobs.save(job).getIngestionJobId();
    }

    @Test
    void staleWriteIsRejectedInsteadOfOverwritingTheOtherEdit() {
        int id = newJob();
        IngestionJob first = jobs.getById(id).orElseThrow();
        IngestionJob second = jobs.getById(id).orElseThrow();

        first.setMappedRowsJson("{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"Ada\"}]}");
        jobs.save(first);

        second.setMappedRowsJson("{\"columns\":[\"FirstName\"],\"rows\":[{\"FirstName\":\"stale\"}]}");
        assertThatThrownBy(() -> jobs.save(second))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(jobs.getById(id).orElseThrow().getMappedRowsJson()).contains("Ada");
    }

    @Test
    void versionAdvancesOnEachSave() {
        int id = newJob();
        IngestionJob job = jobs.getById(id).orElseThrow();
        long before = job.getRowVersion();

        job.setErrorMessage("something went wrong");
        jobs.save(job);

        assertThat(jobs.getById(id).orElseThrow().getRowVersion()).isGreaterThan(before);
    }
}
