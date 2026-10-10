package com.modocument.infrastructure.ai;

import com.modocument.domain.model.FallbackReason;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-EXT-03: every AI failure maps to its fallback reason; only endpoint health counts for the breaker. */
class AiFailureClassifierTest {

    /** How the parse stage wraps a failed call. */
    private static LlmExtractionFailedException wrapped(RuntimeException cause) {
        return new LlmExtractionFailedException(
                "LLM table extraction failed during combined extraction: " + cause.getMessage(), cause);
    }

    @Test
    void availabilityRefusalsKeepTheirOwnReason() {
        assertThat(AiFailureClassifier.classify(AiUnavailableException.circuitOpen(30)))
                .isEqualTo(FallbackReason.CIRCUIT_OPEN);
        assertThat(AiFailureClassifier.classify(wrapped(AiUnavailableException.disabledForBank())))
                .isEqualTo(FallbackReason.AI_DISABLED);
        assertThat(AiFailureClassifier.classify(new AiNotConfiguredException("no key")))
                .isEqualTo(FallbackReason.NOT_CONFIGURED);
    }

    @Test
    void timeoutsAndUnreachableEndpointsAreTimeout() {
        assertThat(AiFailureClassifier.classify(wrapped(new ResourceAccessException(
                "I/O error on POST request", new HttpTimeoutException("request timed out")))))
                .isEqualTo(FallbackReason.TIMEOUT);
        assertThat(AiFailureClassifier.classify(new ResourceAccessException(
                "I/O error", new SocketTimeoutException("Read timed out"))))
                .isEqualTo(FallbackReason.TIMEOUT);
        assertThat(AiFailureClassifier.classify(new ResourceAccessException(
                "I/O error", new ConnectException("Connection refused"))))
                .isEqualTo(FallbackReason.TIMEOUT);
    }

    @Test
    void providerErrorsAreError() {
        assertThat(AiFailureClassifier.classify(wrapped(new TransientAiException("HTTP 500 - boom"))))
                .isEqualTo(FallbackReason.ERROR);
        assertThat(AiFailureClassifier.classify(new NonTransientAiException("HTTP 401 - bad key")))
                .isEqualTo(FallbackReason.ERROR);
        assertThat(AiFailureClassifier.classify(
                new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))
                .isEqualTo(FallbackReason.ERROR);
    }

    @Test
    void scannerAndGatewayRefusalsHaveTheirReasons() {
        assertThat(AiFailureClassifier.classify(wrapped(
                new AiOutputRejectedException("withheld"))))
                .isEqualTo(FallbackReason.OUTPUT_REJECTED);
        assertThat(AiFailureClassifier.classify(new AiPromptRefusedException("two banks")))
                .isEqualTo(FallbackReason.PROMPT_REFUSED);
    }

    @Test
    void anUnusableReplyIsInvalidOutput() {
        assertThat(AiFailureClassifier.classify(wrapped(new RuntimeException(
                "JsonMappingException: Unexpected end-of-input"))))
                .isEqualTo(FallbackReason.INVALID_OUTPUT);
        assertThat(AiFailureClassifier.classify(
                new LlmExtractionFailedException("LLM combined extraction returned no rows.")))
                .isEqualTo(FallbackReason.INVALID_OUTPUT);
    }

    @Test
    void anythingElseIsError() {
        assertThat(AiFailureClassifier.classify(new IllegalStateException("surprise")))
                .isEqualTo(FallbackReason.ERROR);
        // Parallel batches arrive wrapped by CompletableFuture.
        assertThat(AiFailureClassifier.classify(new CompletionException(wrapped(
                new TransientAiException("HTTP 503")))))
                .isEqualTo(FallbackReason.ERROR);
    }

    @Test
    void onlyTransportTimeoutsAndServerErrorsCountForTheCircuit() {
        assertThat(AiFailureClassifier.countsForCircuit(new ResourceAccessException("refused"))).isTrue();
        assertThat(AiFailureClassifier.countsForCircuit(new TransientAiException("HTTP 500"))).isTrue();
        assertThat(AiFailureClassifier.countsForCircuit(
                new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
        assertThat(AiFailureClassifier.countsForCircuit(wrapped(
                new ResourceAccessException("x", new HttpTimeoutException("t"))))).isTrue();

        assertThat(AiFailureClassifier.countsForCircuit(new NonTransientAiException("HTTP 400"))).isFalse();
        assertThat(AiFailureClassifier.countsForCircuit(
                new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS))).isFalse();
        assertThat(AiFailureClassifier.countsForCircuit(new AiOutputRejectedException("x"))).isFalse();
        assertThat(AiFailureClassifier.countsForCircuit(new AiPromptRefusedException("x"))).isFalse();
        assertThat(AiFailureClassifier.countsForCircuit(wrapped(new RuntimeException("bad json")))).isFalse();
    }

    @Test
    void onlyAnUnusableReplyIsWorthACorrectiveRePrompt() {
        assertThat(AiFailureClassifier.isReplyProblem(new RuntimeException("JsonParseException"))).isTrue();
        assertThat(AiFailureClassifier.isReplyProblem(new TransientAiException("HTTP 500"))).isFalse();
        assertThat(AiFailureClassifier.isReplyProblem(new ResourceAccessException("timed out"))).isFalse();
        assertThat(AiFailureClassifier.isReplyProblem(AiUnavailableException.circuitOpen(5))).isFalse();
        assertThat(AiFailureClassifier.isReplyProblem(new AiOutputRejectedException("x"))).isFalse();
    }

    @Test
    void aCyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(AiFailureClassifier.classify(a)).isEqualTo(FallbackReason.ERROR);
    }
}
