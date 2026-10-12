package com.modocument.infrastructure.prompt;

import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

import java.util.Collection;
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
        return lookup(strategies, type);
    }

    /**
     * ws28: THE prompt lookup — the factory and every use case that holds its
     * own strategy list (schema inference, extraction summaries, record
     * cleaning, chat edits) resolve through here instead of four copies of
     * the same stream. An unknown type is a wiring error.
     */
    public static PromptStrategy lookup(Collection<? extends PromptStrategy> strategies, String type) {
        for (PromptStrategy strategy : strategies) {
            if (strategy.getType().equals(type)) {
                return strategy;
            }
        }
        throw new IllegalStateException(type + " prompt not registered.");
    }
}
