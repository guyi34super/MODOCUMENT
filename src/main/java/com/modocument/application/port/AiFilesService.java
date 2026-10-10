package com.modocument.application.port;

import org.springframework.core.ParameterizedTypeReference;

/**
 * Sends prompts to the LLM and returns the reply. The only implementation is
 * the AI gateway (infrastructure/ai/AiGateway, SUP-S13), which is the single
 * component allowed to touch the Spring AI chat client: every call carries a
 * {@link PromptEnvelope} naming its purpose and tenant, is refused when the
 * bound tenant is missing/mismatched or when the assembled prompt still
 * contains a raw identifier shape (call sites tokenize first), and has its
 * completion scanned before it is returned (gate C10).
 */
public interface AiFilesService {

    /**
     * Whether an API key is configured at all. Features that degrade
     * gracefully check this first so an unconfigured deployment doesn't spend
     * per-user quota (or a request) on calls guaranteed to fail.
     */
    boolean isConfigured();

    /** Sends the envelope's prompt and returns the model's raw reply text. */
    String sendPrompt(PromptEnvelope envelope);

    /**
     * Sends the envelope's prompt and converts the reply into {@code type}
     * via the model's structured-output support (JSON-schema format
     * instructions + lenient parsing).
     */
    <T> T sendForObject(PromptEnvelope envelope, ParameterizedTypeReference<T> type);
}
