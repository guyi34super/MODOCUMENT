package com.modocument.doccreation.parse;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * The one entry point for breaking a blueprint into sections: AI first (it
 * reads the document like a human and handles headings in any disguise), the
 * deterministic heuristic parser as the always-available fallback — so upload
 * keeps working with no API key, an unreachable provider, or a bad AI reply.
 */
@Component
public class BlueprintSectionExtractor {

    /** How the sections were produced; surfaced to the UI. */
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_HEURISTIC = "HEURISTIC";

    private final AiSectionSplitter aiSplitter;
    private final BlueprintSectionParser heuristicParser;

    public BlueprintSectionExtractor(AiSectionSplitter aiSplitter, BlueprintSectionParser heuristicParser) {
        this.aiSplitter = aiSplitter;
        this.heuristicParser = heuristicParser;
    }

    public Extraction extract(Path docxPath, String fallbackTitle) {
        return aiSplitter.split(docxPath)
                .map(sections -> new Extraction(sections, SOURCE_AI))
                .orElseGet(() -> new Extraction(heuristicParser.parse(docxPath, fallbackTitle), SOURCE_HEURISTIC));
    }

    public record Extraction(List<ParsedSection> sections, String source) {
    }
}
