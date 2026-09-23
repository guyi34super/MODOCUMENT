package com.modocument.doccreation.flow;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves which {@link DocumentCreationFlow} handles a blueprint, by company
 * id, falling back to the DEFAULT flow (mirrors PromptFactory's strategy-bean
 * lookup). Company-specific flows plug in as additional beans; nothing else
 * changes.
 */
@Component
public class DocumentCreationFlowRegistry {

    private final List<DocumentCreationFlow> flows;
    private final DocumentCreationFlow defaultFlow;

    public DocumentCreationFlowRegistry(List<DocumentCreationFlow> flows) {
        this.flows = flows;
        this.defaultFlow = flows.stream()
                .filter(f -> DocumentCreationFlow.DEFAULT_FLOW_KEY.equals(f.flowKey()))
                .reduce((a, b) -> {
                    throw new IllegalStateException("Multiple DEFAULT document-creation flows registered.");
                })
                .orElseThrow(() -> new IllegalStateException("No DEFAULT document-creation flow registered."));
    }

    public DocumentCreationFlow flowFor(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            return defaultFlow;
        }
        return flows.stream()
                .filter(f -> !DocumentCreationFlow.DEFAULT_FLOW_KEY.equals(f.flowKey()))
                .filter(f -> f.appliesTo(companyId))
                .findFirst()
                .orElse(defaultFlow);
    }
}
