package com.modocument.application.usecase;

import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The valid-row rule shared by the integration API results and the record index. */
class ValidatedRowsTest {

    private static final String TABLE = "{\"columns\":[\"name\",\"nic\"],\"rows\":["
            + "{\"name\":\"Jane\",\"nic\":\"J1\"},{\"name\":\"Bob\",\"nic\":\"\"},{\"name\":\"Zoe\",\"nic\":\"Z3\"}]}";

    private static IngestionJob job(JobStep step, String validation) {
        IngestionJob job = new IngestionJob();
        job.setCurrentStep(step);
        job.setMappedRowsJson(TABLE);
        job.setValidationResultJson(validation);
        return job;
    }

    @Test
    void validRowsAreTheMappedRowsWithoutAFailure() {
        // Row 2 fails; a passing entry (errorMessage null, legacy checkpoints) never rejects.
        ValidatedRows.Split split = ValidatedRows.of(job(JobStep.VALIDATED,
                "[{\"row\":2,\"fieldName\":\"nic\",\"rawValue\":\"\",\"errorMessage\":\"required\"},"
                        + "{\"row\":3,\"fieldName\":\"nic\",\"rawValue\":\"Z3\",\"errorMessage\":null}]"));

        assertThat(split.validated()).isTrue();
        assertThat(split.validRowIndexes()).containsExactly(1, 3);
        assertThat(split.valid()).extracting(row -> row.get("name")).containsExactly("Jane", "Zoe");
        assertThat(split.rejectedRows()).containsOnlyKeys(2);
        assertThat(split.errorsByRow().get(2)).extracting(e -> e.get("field")).containsExactly("nic");
    }

    @Test
    void exportedJobsCountAndUnvalidatedJobsHaveNoValidRows() {
        assertThat(ValidatedRows.of(job(JobStep.EXPORTED, "[]")).validRowIndexes()).containsExactly(1, 2, 3);
        assertThat(ValidatedRows.of(job(JobStep.EXPORTED, null)).valid()).hasSize(3);
        ValidatedRows.Split mapped = ValidatedRows.of(job(JobStep.MAPPED, "[]"));
        assertThat(mapped.validated()).isFalse();
        assertThat(mapped.valid()).isEmpty();
        IngestionJob noRows = job(JobStep.VALIDATED, "[]");
        noRows.setMappedRowsJson(null);
        assertThat(ValidatedRows.of(noRows).validated()).isFalse();
    }
}
