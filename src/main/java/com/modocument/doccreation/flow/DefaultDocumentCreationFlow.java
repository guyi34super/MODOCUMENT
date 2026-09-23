package com.modocument.doccreation.flow;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The main flow every company gets unless a company-specific bean claims them:
 * sections straight from the document's headings, images auto-assigned by
 * filename keywords, generation exactly as assembled.
 */
@Component
public class DefaultDocumentCreationFlow implements DocumentCreationFlow {

    private final KeywordSectionMatcher matcher;

    public DefaultDocumentCreationFlow(KeywordSectionMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public String flowKey() {
        return DEFAULT_FLOW_KEY;
    }

    /** The default is the registry's fallback, never matched by company id. */
    @Override
    public boolean appliesTo(String companyId) {
        return false;
    }

    @Override
    public Optional<Integer> assignSection(String originalFileName, List<DocBlueprintSection> sections) {
        return matcher.match(originalFileName, sections);
    }
}
