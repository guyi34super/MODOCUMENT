package com.modocument.infrastructure.ai;

/**
 * The model's completion was withheld by the output scanner (gate C10): it
 * contained a raw identifier shape that was never part of the request, or it
 * echoed injected instructions. Extends IllegalStateException so every
 * caller's existing degrade-gracefully catch block treats it as "the AI
 * misbehaved, use the non-AI path".
 */
public class AiOutputRejectedException extends IllegalStateException {

    public AiOutputRejectedException(String message) {
        super(message);
    }
}
