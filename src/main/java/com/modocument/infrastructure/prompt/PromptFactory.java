package com.modocument.infrastructure.prompt;

import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves a {@link PromptStrategy} by its type name. All strategy beans are
 * injected, so registering a new strategy is just adding a component.
 */
@Component
public class PromptFactory {

    private final List<PromptStrategy> strategies;

    public PromptFactory(List<PromptStrategy> strategies) {
        this.strategies = strategies;
    }

    public PromptStrategy getPrompt(String type) {
        return strategies.stream()
                .filter(s -> s.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown prompt type"));
    }
}
