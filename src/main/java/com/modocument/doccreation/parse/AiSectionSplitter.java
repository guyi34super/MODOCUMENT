package com.modocument.doccreation.parse;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * AI-driven document breakdown: sends the blueprint's numbered paragraph
 * listing to the LLM and asks it to identify every logical section — the way a
 * careful human reader would — instead of relying on heading styles alone,
 * which many real-world documents don't use consistently.
 *
 * <p>The model only ever CHOOSES among the real paragraph indexes it was
 * shown; every anchor is re-validated against the document and the anchor
 * text is taken from the document itself, never from the model — so the
 * generation engine's exact-reproduction guarantee is unaffected. Any
 * invalid or failed reply makes this splitter step aside (empty result) and
 * the caller falls back to the heuristic parser.
 */
@Component
public class AiSectionSplitter {

    private static final Logger log = LoggerFactory.getLogger(AiSectionSplitter.class);

    /** Keep the paragraph digest within a local model's context window. */
    static final int MAX_PARAGRAPH_CHARS = 200;
    static final int MAX_DIGEST_CHARS = 24_000;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_KEYWORDS_PER_SECTION = 12;

    private static final String PROMPT = """
            You are an expert document-structure analyst. Below is the full body listing of a \
            business document (.docx). Each line is one body element: "[index] text" for a \
            paragraph (with STYLE= and BOLD markers when present) or "[index] <TABLE>" for a table.

            Break the ENTIRE document into its logical sections — exactly the sections a careful \
            human reader would use as chapters to file photos and notes under. Follow every rule:
            - A section starts at the paragraph that acts as its heading or title line. Headings \
            come in many disguises: styled headings, numbered lines ("3.1 Earthing"), short \
            standalone lines in bold or capitals, labelled parts ("Scope of Works:"), etc.
            - anchorIndex is the exact [index] of that heading paragraph. Never invent an index, \
            never point at a <TABLE> line, never reuse an index. anchorIndex values must be \
            strictly increasing.
            - Cover the whole document: every meaningful part of it belongs to some section. \
            Prefer the document's own structure over inventing one. Include sub-sections when \
            they are clearly delineated and useful as separate slots.
            - Do NOT treat ordinary body sentences, list items, captions, page headers/footers or \
            table content as sections.
            - headingLevel: 1 for top-level sections, 2-9 for nested ones (from numbering depth, \
            styles or indentation).
            - title: the heading's clean text, kept faithful to the document.
            - keywords: 3-8 short lowercase tokens someone would put in a photo file name meant \
            for that section (e.g. "roof", "dc2", "inverter", "earthing"). Always include codes \
            and abbreviations appearing in the heading.
            - If the document truly has no identifiable sections, return an empty array [].

            Document listing:
            """;

    private final AiFilesService ai;
    private final LlmRateLimiter llmQuota;
    private final CurrentUserProvider currentUser;
    private final boolean enabled;
    private final String modelId;

    public AiSectionSplitter(AiFilesService ai,
                             LlmRateLimiter llmQuota,
                             CurrentUserProvider currentUser,
                             @Value("${modocument.doccreation.section-ai-enabled:true}") boolean enabled,
                             @Value("${modocument.doccreation.section-model-id:}") String modelId) {
        this.ai = ai;
        this.llmQuota = llmQuota;
        this.currentUser = currentUser;
        this.enabled = enabled;
        this.modelId = modelId;
    }

    /** The shape the model must return; validated before anything is trusted. */
    public record AiSection(Integer anchorIndex, String title, Integer headingLevel, List<String> keywords) {
    }

    /**
     * @return the AI's validated breakdown, or empty when AI is disabled,
     *     unavailable, or returned anything that doesn't check out against the
     *     real document — the caller then uses the heuristic parser.
     */
    public Optional<List<ParsedSection>> split(Path docxPath) {
        if (!enabled) {
            return Optional.empty();
        }
        List<IBodyElement> elements;
        String digest;
        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            elements = List.copyOf(doc.getBodyElements());
            digest = digest(elements);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the blueprint document.", e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The uploaded file is not a readable .docx document.", e);
        }
        if (digest.isBlank()) {
            return Optional.empty();
        }

        List<AiSection> reply;
        try {
            // Meters the same per-user LLM quota as the other AI endpoints;
            // an exhausted quota degrades to the heuristic instead of failing
            // the upload.
            llmQuota.check(currentUser.requireUserId());
            reply = ai.sendForObject(PROMPT + digest,
                    new ParameterizedTypeReference<List<AiSection>>() {
                    }, modelId);
        } catch (RuntimeException e) {
            // No key, provider down, model nonsense — all mean "use the heuristic".
            log.warn("AI section breakdown unavailable, falling back to heuristics: {}", e.getMessage());
            return Optional.empty();
        }
        return validate(reply, elements);
    }

    /** One line per body element, capped per paragraph and in total (shared with AI composition). */
    public static String digest(List<IBodyElement> elements) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (out.length() >= MAX_DIGEST_CHARS) {
                out.append("[…] (listing truncated)\n");
                break;
            }
            if (elements.get(i) instanceof XWPFParagraph paragraph) {
                String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.length() > MAX_PARAGRAPH_CHARS) {
                    text = text.substring(0, MAX_PARAGRAPH_CHARS) + "…";
                }
                out.append('[').append(i).append("] ").append(text);
                String style = paragraph.getStyle();
                if (style != null && !style.isBlank()) {
                    out.append("  STYLE=").append(style);
                }
                if (isAllBold(paragraph)) {
                    out.append("  BOLD");
                }
                out.append('\n');
            } else {
                out.append('[').append(i).append("] <TABLE>\n");
            }
        }
        return out.toString();
    }

    private static boolean isAllBold(XWPFParagraph paragraph) {
        boolean sawText = false;
        for (XWPFRun run : paragraph.getRuns()) {
            String text = run.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            sawText = true;
            if (!run.isBold()) {
                return false;
            }
        }
        return sawText;
    }

    /**
     * Every anchor must be a real, non-blank paragraph and the anchors must be
     * strictly increasing; titles fall back to and anchor text always comes
     * from the document itself. Anything off → empty (heuristic fallback).
     */
    private static Optional<List<ParsedSection>> validate(List<AiSection> reply, List<IBodyElement> elements) {
        if (reply == null || reply.isEmpty()) {
            return Optional.empty();
        }
        List<ParsedSection> sections = new ArrayList<>();
        int previousAnchor = -1;
        for (AiSection section : reply) {
            if (section == null || section.anchorIndex() == null) {
                return invalid("a section without an anchorIndex");
            }
            int anchor = section.anchorIndex();
            if (anchor <= previousAnchor) {
                return invalid("anchors out of order (" + anchor + " after " + previousAnchor + ")");
            }
            if (anchor < 0 || anchor >= elements.size()
                    || !(elements.get(anchor) instanceof XWPFParagraph paragraph)) {
                return invalid("anchorIndex " + anchor + " is not a paragraph of the document");
            }
            String paragraphText = paragraph.getText() == null ? "" : paragraph.getText().strip();
            if (paragraphText.isEmpty()) {
                return invalid("anchorIndex " + anchor + " points at an empty paragraph");
            }
            String title = section.title() == null || section.title().isBlank()
                    ? paragraphText : section.title().strip();
            int level = section.headingLevel() == null ? 1
                    : Math.max(1, Math.min(9, section.headingLevel()));
            sections.add(new ParsedSection(sections.size(), truncate(title), level, anchor,
                    truncate(paragraphText), mergeKeywords(section.keywords(), title)));
            previousAnchor = anchor;
        }
        return Optional.of(sections);
    }

    private static Optional<List<ParsedSection>> invalid(String why) {
        log.warn("AI section breakdown rejected ({}), falling back to heuristics", why);
        return Optional.empty();
    }

    /** The model's keywords, sanitized, plus the title-seeded ones the heuristic would use. */
    private static List<String> mergeKeywords(List<String> aiKeywords, String title) {
        Set<String> merged = new LinkedHashSet<>();
        if (aiKeywords != null) {
            for (String keyword : aiKeywords) {
                if (keyword == null) {
                    continue;
                }
                String cleaned = keyword.strip().toLowerCase(Locale.ROOT);
                if (!cleaned.isEmpty() && cleaned.length() <= 40 && !cleaned.contains(",")) {
                    merged.add(cleaned);
                }
                if (merged.size() >= MAX_KEYWORDS_PER_SECTION) {
                    break;
                }
            }
        }
        merged.addAll(BlueprintSectionParser.seedKeywords(title));
        return List.copyOf(merged);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }
}
