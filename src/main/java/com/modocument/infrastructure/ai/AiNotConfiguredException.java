package com.modocument.infrastructure.ai;

/**
 * No usable API key for the AI provider. Its own type (rather than a generic
 * IllegalStateException) so the web layer can answer 503 with an explanation
 * aimed at the operator, instead of a 409 quoting a configuration key at an
 * end user.
 */
public class AiNotConfiguredException extends IllegalStateException {

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
