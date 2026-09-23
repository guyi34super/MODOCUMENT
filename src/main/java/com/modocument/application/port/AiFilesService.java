package com.modocument.application.port;

import org.springframework.core.ParameterizedTypeReference;

/** Sends prompts to the LLM (OpenAI chat completion) and returns the raw reply. */
public interface AiFilesService {

    String sendPrompt(String prompt);

    /**
     * Sends a prompt using {@code modelId} for this call (e.g. a task-specific
     * local model). When blank, the global default from configuration is used.
     */
    String sendPrompt(String prompt, String modelId);

    /**
     * Sends a prompt and converts the reply into {@code type} via the model's
     * structured-output support (JSON-schema format instructions + parsing).
     */
    <T> T sendForObject(String prompt, ParameterizedTypeReference<T> type);

    /**
     * Same as {@link #sendForObject(String, ParameterizedTypeReference)} but
     * uses {@code modelId} for this call (e.g. a chat-specific local model).
     */
    <T> T sendForObject(String prompt, ParameterizedTypeReference<T> type, String modelId);
}
