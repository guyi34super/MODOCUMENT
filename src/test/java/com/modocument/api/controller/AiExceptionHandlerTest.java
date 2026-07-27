package com.modocument.api.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {

    @Test
    void mapsOpenRouter402ToFriendlyCreditsMessage() {
        String raw = "HTTP 402 - {\"error\":{\"message\":\"This request requires more credits\"}}";
        assertThat(AiExceptionHandler.friendlyMessage(raw))
                .contains("credits are insufficient");
    }

    @Test
    void extractsJsonMessageWhenPresent() {
        String raw = "HTTP 500 - {\"error\":{\"message\":\"Model overloaded\"}}";
        assertThat(AiExceptionHandler.friendlyMessage(raw)).isEqualTo("Model overloaded");
    }
}
