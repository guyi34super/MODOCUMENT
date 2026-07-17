package com.modocument.application.port;

import org.springframework.core.ParameterizedTypeReference;

/** Sends prompts to the LLM (OpenAI chat completion) and returns the raw reply. */
public interface AiFilesService {

    String sendPrompt(String prompt);

    /**
     * Sends a prompt and converts the reply into {@code type} via the model's
     * structured-output support (JSON-schema format instructions + parsing).
     */
    <T> T sendForObject(String prompt, ParameterizedTypeReference<T> type);
}
