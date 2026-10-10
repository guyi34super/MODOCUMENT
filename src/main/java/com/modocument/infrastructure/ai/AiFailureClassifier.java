package com.modocument.infrastructure.ai;

import com.modocument.domain.model.FallbackReason;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Maps an AI call's failure to the {@link FallbackReason} the parse stage
 * records (ws23), and decides which failures count against the
 * {@link AiCircuitBreaker}. The whole cause chain is inspected, because the
 * parse stage wraps the provider's exception in
 * {@link LlmExtractionFailedException}; the most specific cause wins:
 *
 * <ol>
 *   <li>{@link AiUnavailableException} → its own reason (CIRCUIT_OPEN, AI_DISABLED);</li>
 *   <li>{@link AiNotConfiguredException} → NOT_CONFIGURED;</li>
 *   <li>{@link AiPromptRefusedException} → PROMPT_REFUSED;</li>
 *   <li>{@link AiOutputRejectedException} → OUTPUT_REJECTED;</li>
 *   <li>transport failures and timeouts ({@link ResourceAccessException},
 *       socket/HTTP timeouts, interrupted I/O, a cancelled exchange,
 *       refused connections) → TIMEOUT;</li>
 *   <li>provider errors ({@link TransientAiException} after retries,
 *       {@link NonTransientAiException}, HTTP error responses) → ERROR;</li>
 *   <li>{@link LlmExtractionFailedException} with none of the above → INVALID_OUTPUT;</li>
 *   <li>anything else → ERROR.</li>
 * </ol>
 */
public final class AiFailureClassifier {

    private static final int MAX_DEPTH = 20;

    private AiFailureClassifier() {
    }

    public static FallbackReason classify(Throwable error) {
        List<Throwable> chain = causeChain(error);
        for (Throwable t : chain) {
            if (t instanceof AiUnavailableException unavailable && unavailable.reason() != null) {
                return unavailable.reason();
            }
        }
        if (any(chain, AiNotConfiguredException.class)) {
            return FallbackReason.NOT_CONFIGURED;
        }
        if (any(chain, AiPromptRefusedException.class)) {
            return FallbackReason.PROMPT_REFUSED;
        }
        if (any(chain, AiOutputRejectedException.class)) {
            return FallbackReason.OUTPUT_REJECTED;
        }
        for (Throwable t : chain) {
            if (isTimeoutOrUnreachable(t)) {
                return FallbackReason.TIMEOUT;
            }
        }
        for (Throwable t : chain) {
            if (isProviderError(t)) {
                return FallbackReason.ERROR;
            }
        }
        if (any(chain, LlmExtractionFailedException.class)) {
            return FallbackReason.INVALID_OUTPUT;
        }
        return FallbackReason.ERROR;
    }

    /**
     * Whether the failure says the AI endpoint is unhealthy — a transport
     * error, a timeout or a server-side (5xx) error — and so counts against
     * the circuit breaker. Client errors (4xx), refusals, scanner rejections
     * and unusable replies do not: the endpoint answered.
     */
    public static boolean countsForCircuit(Throwable error) {
        for (Throwable t : causeChain(error)) {
            if (isTimeoutOrUnreachable(t) || t instanceof TransientAiException
                    || t instanceof HttpServerErrorException
                    || (t instanceof RestClientResponseException response
                    && response.getStatusCode().is5xxServerError())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the model answered but its reply was unusable (bad JSON, no
     * rows) — the only failure a corrective re-prompt can fix. Transport,
     * provider, availability and policy failures are not worth a second
     * prompt: it would fail the same way and only delay the fallback.
     */
    public static boolean isReplyProblem(Throwable error) {
        List<Throwable> chain = causeChain(error);
        for (Throwable t : chain) {
            if (t instanceof AiNotConfiguredException || t instanceof AiPromptRefusedException
                    || t instanceof AiOutputRejectedException || isTimeoutOrUnreachable(t)
                    || isProviderError(t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * BUG-03: a model read timeout can surface as a bare
     * {@link CancellationException} (the HTTP client cancels the exchange) or
     * an {@link InterruptedIOException}; both are timeouts — they count for
     * the breaker and never earn a corrective re-prompt.
     */
    private static boolean isTimeoutOrUnreachable(Throwable t) {
        return t instanceof ResourceAccessException
                || t instanceof CancellationException
                || t instanceof InterruptedIOException
                || t instanceof SocketTimeoutException
                || t instanceof HttpTimeoutException
                || t instanceof ConnectException
                || t instanceof TimeoutException;
    }

    private static boolean isProviderError(Throwable t) {
        return t instanceof TransientAiException
                || t instanceof NonTransientAiException
                || t instanceof RestClientResponseException;
    }

    private static boolean any(List<Throwable> chain, Class<? extends Throwable> type) {
        for (Throwable t : chain) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && chain.size() < MAX_DEPTH && seen.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}
