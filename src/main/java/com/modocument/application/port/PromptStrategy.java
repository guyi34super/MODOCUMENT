package com.modocument.application.port;

import com.modocument.application.dto.PromptInput;

public interface PromptStrategy {

    String buildPrompt(PromptInput input);

    String getType();
}
