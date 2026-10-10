package com.modocument.application.dto;

import com.modocument.domain.entity.IngestionJob;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4 (AC-EXT-07): the job view carries the bank's code-confidence
 * threshold and whether a code-scored table fell below it — the job page's
 * low-confidence callout.
 */
class IngestionJobDtoTest {

    private static IngestionJobDto view(String parsedBy, String confidence, String reason) {
        IngestionJob job = new IngestionJob();
        job.setParsedBy(parsedBy);
        job.setParseStrategy("KEY_VALUE");
        job.setParseConfidence(confidence == null ? null : new BigDecimal(confidence));
        job.setParseFallbackReason(reason);
        return IngestionJobDto.from(job, List.of(), List.of());
    }

    @Test
    void aCodeOnlyTableBelowTheThresholdIsLowConfidence() {
        IngestionJobDto judged = view("CODE", "0.520", "MODE_CODE_ONLY").withConfidenceThreshold(0.75);

        assertThat(judged.parseMinConfidence()).isEqualTo(0.75);
        assertThat(judged.parseLowConfidence()).isTrue();
        assertThat(judged.parseConfidence()).isEqualTo(0.52);
    }

    @Test
    void atOrAboveTheThresholdIsNotLowConfidence() {
        assertThat(view("CODE", "0.750", "QUOTA").withConfidenceThreshold(0.75).parseLowConfidence())
                .isFalse();
        assertThat(view("AI_PARTIAL", "0.910", "ERROR").withConfidenceThreshold(0.8).parseLowConfidence())
                .isFalse();
    }

    @Test
    void withoutACodeScoreNothingIsJudged() {
        IngestionJobDto ai = view("AI", null, null).withConfidenceThreshold(0.75);
        assertThat(ai.parseMinConfidence()).isNull();
        assertThat(ai.parseLowConfidence()).isNull();

        IngestionJobDto fastPath = view("CODE", null, null).withConfidenceThreshold(0.75);
        assertThat(fastPath.parseLowConfidence()).isNull();
    }
}
