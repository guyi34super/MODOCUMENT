package com.modocument.infrastructure.ai;

/**
 * The AI gateway refused to send a prompt, or refused the reply as an echo of
 * its own instructions (gate C9): the call is not bound to exactly one bank,
 * the prompt still carries a raw identifier shape, or the model repeated its
 * formatting instructions. Typed so the ingestion parse stage can record the
 * fallback reason {@code PROMPT_REFUSED}; extends IllegalStateException so
 * every existing caller keeps treating it exactly as before (409 at the API,
 * "use the non-AI path" in degrade-gracefully catch blocks). Falling back to
 * code is privacy-safe: the data never left the backend.
 */
public class AiPromptRefusedException extends IllegalStateException {

    public AiPromptRefusedException(String message) {
        super(message);
    }
}
