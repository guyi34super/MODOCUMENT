package com.modocument.infrastructure.ai;

/**
 * No usable API key for the AI provider.
 *
 * <p>Deliberately NOT an IllegalStateException: controllers map that to 409
 * locally, and a controller-local handler wins over any advice — which is how
 * "no API key" used to reach the user as a conflict quoting a configuration
 * key. As its own type it lands on the 503 handler in
 * {@link com.modocument.api.controller.AiExceptionHandler}.
 */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
