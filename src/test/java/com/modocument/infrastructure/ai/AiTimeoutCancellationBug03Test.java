package com.modocument.infrastructure.ai;

import com.modocument.domain.model.FallbackReason;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-03 (QA, phase 2 Wave 2) — regression test, disabled until fixed.
 *
 * <p>Observed in a full {@code mvn -B verify}: with a model that accepts the
 * request and never answers, Spring's JDK request factory sometimes reports
 * the read timeout as a bare {@link CancellationException} (the timeout
 * handler cancels the response future) instead of
 * {@code ResourceAccessException(HttpTimeoutException)}. The log of that run:
 *
 * <pre>
 * SpringAiRetryAutoConfiguration : Retry error. Retry count: 1, Exception: null
 * java.util.concurrent.CancellationException: null
 * IngestionParseStepImpl : Combined extraction JSON parse failed; retrying once: null
 * SpringAiRetryAutoConfiguration : Retry error. ... Request timed out
 * IngestionParseStepImpl : AI extraction failed (TIMEOUT ...); falling back to code
 * </pre>
 *
 * {@link AiFailureClassifier} does not know the exception, so a hung model is
 * (1) treated as an unusable REPLY — the parse stage sends a corrective
 * re-prompt to the same hung endpoint, doubling the wait before the code
 * fallback (2 x 120 s with the production read timeout); (2) not counted
 * against the circuit breaker; (3) recorded as ERROR instead of TIMEOUT when
 * it is the last failure. {@code IngestionFallbackIntegrationTest
 * .aModelThatNeverAnswersTimesOutAndFallsBackToCode} fails intermittently
 * because of it ("expected: 1 but was: 2" model calls).
 *
 * <p>Expected: a {@code CancellationException} anywhere in the chain of an
 * AI call is a timeout/transport failure, like {@code HttpTimeoutException}.
 * Owner: SE4 (ws23 classifier).
 */
class AiTimeoutCancellationBug03Test {

    private static LlmExtractionFailedException wrapped(RuntimeException cause) {
        return new LlmExtractionFailedException(
                "LLM table extraction failed during combined extraction: " + cause.getMessage(),
                cause);
    }

    @Test
    @Disabled("BUG-03: a read timeout surfacing as CancellationException is re-prompted, "
            + "not counted for the circuit breaker and classified ERROR instead of TIMEOUT")
    void aCancelledModelCallIsATimeoutNotAReplyProblem() {
        CancellationException timedOut = new CancellationException();

        assertThat(AiFailureClassifier.isReplyProblem(timedOut))
                .as("no corrective re-prompt to a hung endpoint").isFalse();
        assertThat(AiFailureClassifier.countsForCircuit(timedOut))
                .as("a hung endpoint is unhealthy").isTrue();
        assertThat(AiFailureClassifier.classify(wrapped(timedOut)))
                .isEqualTo(FallbackReason.TIMEOUT);
    }
}
