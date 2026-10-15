package com.modocument.doccreation.parse;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** Appended when the listing was cut; AI steps aside rather than work blind. */
    public static final String TRUNCATION_MARKER = "(listing truncated)";
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_KEYWORDS_PER_SECTION = 12;
    static final int CONDENSED_PARAGRAPH_CHARS = 120;
    private static final java.util.regex.Pattern CANDIDATE_NUMBERING = java.util.regex.Pattern.compile(
            "^(\\d+(\\.\\d+)*\\.?\\s+\\S|[IVXLC]+\\.\\s+\\S|[A-Z][.)]\\s+\\S|\\([a-z0-9]{1,3}\\)\\s+\\S"
                    + "|(?i:section|part|chapter|schedule|annex(ure)?|appendix|article|clause)\\s+[\\w.]+)");
    private static final String CONDENSED_NOTE = """
            (This document is long, so the listing below shows ONLY the lines most likely to be \
            headings; ordinary body paragraphs and tables are left out. The [index] numbers are \
            still the real body-element indexes — anchor to them exactly; gaps are expected.)

            """;

    private static final String PROMPT = """
            You are an expert document-structure analyst building a REUSABLE TEMPLATE from an \
            uploaded business document (.docx). Each line is one body element: "[index] text" for \
            a paragraph (STYLE=, BOLD=, LABEL= when present) or "[index] <TABLE cols=N preview=\"…\">".

            Break the ENTIRE document into logical template sections — the parts a user would fill \
            or edit separately when creating a new document from this template. Document kinds \
            include forms (label/value rows), reports (headings + narrative), schedules/tables, \
            legal (Part/Article/Schedule), and letters (addressee, body, signature).

            Rules (follow all):
            - anchorIndex is the exact [index] of the paragraph that STARTS the section (the \
            heading, part title, or field-group label line). Never invent an index. Never anchor \
            on a <TABLE> line — anchor on the paragraph immediately BEFORE a table if that is the \
            section title. Never reuse an index; order by increasing anchorIndex.
            - COVERAGE: every meaningful paragraph and table must fall between consecutive \
            section anchors (from this anchor up to, but not including, the next). Add a final \
            section for trailing content after the last heading when needed. If the file has no \
            headings at all, return one section anchored at the first non-empty paragraph.
            - Prefer field-GROUP sections (e.g. "Applicant details", "Loan particulars") over one \
            section per single form field. Use headingLevel 2-3 for nested parts under a Part/Chapter.
            - sectionKind (optional): NARRATIVE (prose the user adds to), FORM (label/value fields \
            to fill), TABLE (mostly tabular block), BOILERPLATE (headers, watermarks, legal footers \
            — still list them but mark BOILERPLATE).
            - Do NOT start sections on ordinary body sentences, isolated list items, or page numbers.
            - headingLevel: 1 top-level, 2-9 nested (numbering depth, styles, indentation).
            - title: clean heading text faithful to the document.
            - keywords: 3-8 short lowercase tokens for auto-matching uploads (include codes in headings).
            - If nothing is sectionable, return [].

            Document listing:
            """;

    private final AiFilesService ai;
    private final LlmRateLimiter llmQuota;
    private final CurrentUserProvider currentUser;
    private final PiiTokenizer tokenizer;
    private final boolean enabled;
    private final String modelId;
    /** Null = the yml switch only (unit-test wiring). */
    private final SettingsReader settings;

    public AiSectionSplitter(AiFilesService ai,
                             LlmRateLimiter llmQuota,
                             CurrentUserProvider currentUser,
                             PiiTokenizer tokenizer,
                             boolean enabled,
                             String modelId) {
        this(ai, llmQuota, currentUser, tokenizer, enabled, modelId, null);
    }

    /**
     * ws20: the switch is the setting {@code doccreation.sectionAiEnabled} of
     * the caller's bank AND that bank's AI master switch ({@code ai.enabled}),
     * read per call; with nothing stored, the yml value.
     */
    @Autowired
    public AiSectionSplitter(AiFilesService ai,
                             LlmRateLimiter llmQuota,
                             CurrentUserProvider currentUser,
                             PiiTokenizer tokenizer,
                             @Value("${modocument.doccreation.section-ai-enabled:true}") boolean enabled,
                             @Value("${modocument.doccreation.section-model-id:}") String modelId,
                             SettingsReader settings) {
        this.ai = ai;
        this.llmQuota = llmQuota;
        this.currentUser = currentUser;
        this.tokenizer = tokenizer;
        this.enabled = enabled;
        this.modelId = modelId;
        this.settings = settings;
    }

    /** Whether the section breakdown may ask the AI, for the caller's bank. */
    public boolean enabled() {
        if (settings == null) {
            return enabled;
        }
        return settings.getBoolForCurrentBank(SettingKeys.DOCCREATION_SECTION_AI_ENABLED)
                && settings.getBoolForCurrentBank(SettingKeys.AI_ENABLED);
    }

    /** The shape the model must return; validated before anything is trusted. */
    public record AiSection(Integer anchorIndex,
                            String title,
                            Integer headingLevel,
                            List<String> keywords,
                            String sectionKind) {
    }

    /**
     * @return the AI's validated breakdown, or empty when AI is disabled,
     *     unavailable, or returned anything that doesn't check out against the
     *     real document — the caller then uses the heuristic parser.
     */
    public Optional<List<ParsedSection>> split(Path docxPath) {
        String skipReason = skipReason();
        if (skipReason != null) {
            log.info("AI section breakdown skipped ({}); using the heuristic parser", skipReason);
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
            log.info("AI section breakdown skipped (the document has no paragraph text); using the heuristic parser");
            return Optional.empty();
        }
        String preamble = "";
        if (digest.contains(TRUNCATION_MARKER)) {
            // The model can only anchor to paragraphs it was shown, so a cut
            // listing would lose every section past the cap. Instead it sees
            // only the likely heading lines — still with their real indexes.
            digest = condensedDigest(elements);
            if (digest.isBlank() || digest.contains(TRUNCATION_MARKER)) {
                log.info("AI section breakdown skipped (document too large even for the condensed listing); "
                        + "using the heuristic parser");
                return Optional.empty();
            }
            log.info("Blueprint too large for the full AI listing; sending {} heading candidates",
                    digest.lines().count());
            preamble = CONDENSED_NOTE;
        }

        // C9: one-way masking of identifier shapes in the listing. Masking
        // changes text LENGTHS but not the "[index]" element numbering the
        // model anchors to (anchors are body-element indexes, not character
        // offsets), so re-validation against the real document is unaffected
        // and nothing needs unmasking — titles/anchor text come from the
        // document itself.
        String maskedDigest = tokenizer.maskText(digest);

        List<AiSection> reply;
        try {
            // Meters the same per-user LLM quota as the other AI endpoints;
            // an exhausted quota degrades to the heuristic instead of failing
            // the upload.
            llmQuota.check(currentUser.requireUserId());
            reply = ai.sendForObject(
                    PromptEnvelope.builder(AiPurpose.DOC_SECTION_SPLIT)
                            .user(currentUser.requireUserId())
                            .part(PROMPT + preamble + maskedDigest)
                            .model(modelId)
                            .build(),
                    new ParameterizedTypeReference<List<AiSection>>() {
                    });
        } catch (RuntimeException e) {
            // No key, provider down, model nonsense — all mean "use the heuristic".
            log.warn("AI section breakdown unavailable, falling back to heuristics: {}", e.getMessage());
            return Optional.empty();
        }
        return validate(reply, elements);
    }

    /** Why the AI must not be asked for the caller's bank, or null when it may. */
    private String skipReason() {
        if (settings == null) {
            if (!enabled) {
                return "modocument.doccreation.section-ai-enabled is off";
            }
        } else if (!settings.getBoolForCurrentBank(SettingKeys.DOCCREATION_SECTION_AI_ENABLED)) {
            return "the bank setting " + SettingKeys.DOCCREATION_SECTION_AI_ENABLED + " is off";
        } else if (!settings.getBoolForCurrentBank(SettingKeys.AI_ENABLED)) {
            return "the bank's AI master switch " + SettingKeys.AI_ENABLED + " is off";
        }
        if (!ai.isConfigured()) {
            return "no AI provider is configured";
        }
        return null;
    }

    /**
     * Only the likely heading lines (styled, bold, numbered, capitals, short
     * label lines), shorter per line, keeping the real body-element indexes.
     */
    static String condensedDigest(List<IBodyElement> elements) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) instanceof XWPFTable table) {
                appendCondensedTable(out, i, table);
                continue;
            }
            if (!(elements.get(i) instanceof XWPFParagraph paragraph) || !condensedCandidate(paragraph)) {
                continue;
            }
            if (out.length() >= MAX_DIGEST_CHARS) {
                out.append("[…] ").append(TRUNCATION_MARKER).append('\n');
                break;
            }
            String text = paragraph.getText().strip();
            if (text.length() > CONDENSED_PARAGRAPH_CHARS) {
                text = text.substring(0, CONDENSED_PARAGRAPH_CHARS) + "…";
            }
            out.append('[').append(i).append("] ").append(text);
            String style = paragraph.getStyle();
            if (style != null && !style.isBlank()) {
                out.append("  STYLE=").append(style);
            }
            if (isAllBold(paragraph)) {
                out.append("  BOLD");
            }
            appendLabelMarker(out, text);
            out.append('\n');
        }
        return out.toString();
    }

    private static boolean condensedCandidate(XWPFParagraph paragraph) {
        if (isHeadingCandidate(paragraph)) {
            return true;
        }
        String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
        return text.contains("\t") || text.endsWith(":");
    }

    /** A paragraph that could plausibly be a section heading (shared with the section editor). */
    public static boolean isHeadingCandidate(XWPFParagraph paragraph) {
        String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
        if (text.isEmpty() || text.length() > 160) {
            return false;
        }
        String style = paragraph.getStyle();
        if (style != null && style.toLowerCase(Locale.ROOT).matches(".*(heading|title|berschrift|titre).*")) {
            return true;
        }
        var ppr = paragraph.getCTP().getPPr();
        if (ppr != null && ppr.isSetOutlineLvl()) {
            return true;
        }
        boolean shortLine = text.length() <= 100 && text.split("\\s+").length <= 14;
        if (!shortLine) {
            return false;
        }
        if (isAllBold(paragraph) || text.endsWith(":") || CANDIDATE_NUMBERING.matcher(text).find()) {
            return true;
        }
        String letters = text.replaceAll("[^\\p{L}]", "");
        return letters.length() >= 3 && letters.equals(letters.toUpperCase(Locale.ROOT));
    }

    /** One line per body element, capped per paragraph and in total (shared with AI composition). */
    public static String digest(List<IBodyElement> elements) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            if (out.length() >= MAX_DIGEST_CHARS) {
                out.append("[…] ").append(TRUNCATION_MARKER).append('\n');
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
                appendLabelMarker(out, text);
                out.append('\n');
            } else if (elements.get(i) instanceof XWPFTable table) {
                appendTableLine(out, i, table);
            }
        }
        return out.toString();
    }

    private static void appendTableLine(StringBuilder out, int index, XWPFTable table) {
        out.append('[').append(index).append("] <TABLE cols=").append(tableColumnCount(table))
                .append(" preview=\"").append(escapeAttr(tablePreview(table))).append("\">\n");
    }

    private static void appendCondensedTable(StringBuilder out, int index, XWPFTable table) {
        if (out.length() >= MAX_DIGEST_CHARS) {
            return;
        }
        appendTableLine(out, index, table);
    }

    private static int tableColumnCount(XWPFTable table) {
        if (table.getNumberOfRows() == 0) {
            return 0;
        }
        return table.getRow(0).getTableCells().size();
    }

    static String tablePreview(XWPFTable table) {
        if (table.getNumberOfRows() == 0) {
            return "";
        }
        XWPFTableRow row = table.getRow(0);
        StringBuilder cells = new StringBuilder();
        for (XWPFTableCell cell : row.getTableCells()) {
            if (cells.length() > 0) {
                cells.append(" | ");
            }
            String text = cell.getText() == null ? "" : cell.getText().strip().replaceAll("\\s+", " ");
            if (text.length() > 40) {
                text = text.substring(0, 40) + "…";
            }
            cells.append(text);
        }
        String preview = cells.toString();
        return preview.length() > 120 ? preview.substring(0, 120) + "…" : preview;
    }

    private static void appendLabelMarker(StringBuilder out, String text) {
        int tab = text.indexOf('\t');
        if (tab > 0) {
            out.append("  LABEL=").append(text.substring(0, tab).strip());
        } else if (text.endsWith(":") && text.length() <= 80) {
            out.append("  LABEL=").append(text.substring(0, text.length() - 1).strip());
        }
    }

    private static String escapeAttr(String value) {
        return value.replace("\\", "\\\\").replace("\"", "'");
    }

    static String normalizeSectionKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedSection.KIND_NARRATIVE;
        }
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case ParsedSection.KIND_FORM, ParsedSection.KIND_TABLE, ParsedSection.KIND_BOILERPLATE -> upper;
            default -> ParsedSection.KIND_NARRATIVE;
        };
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
            log.info("AI section breakdown found no sections; using the heuristic parser");
            return Optional.empty();
        }
        // Entries are checked one by one: a bad index or a repeat drops that
        // entry, and the rest are put in document order. Only a reply that is
        // mostly wrong is rejected as a whole.
        java.util.TreeMap<Integer, AiSection> byAnchor = new java.util.TreeMap<>();
        List<String> dropped = new ArrayList<>();
        for (AiSection section : reply) {
            if (section == null || section.anchorIndex() == null) {
                dropped.add("a section without an anchorIndex");
                continue;
            }
            int anchor = section.anchorIndex();
            if (anchor < 0 || anchor >= elements.size()
                    || !(elements.get(anchor) instanceof XWPFParagraph paragraph)) {
                dropped.add("anchorIndex " + anchor + " is not a paragraph of the document");
                continue;
            }
            if (paragraph.getText() == null || paragraph.getText().isBlank()) {
                dropped.add("anchorIndex " + anchor + " points at an empty paragraph");
                continue;
            }
            if (byAnchor.putIfAbsent(anchor, section) != null) {
                dropped.add("anchorIndex " + anchor + " used twice");
            }
        }
        if (byAnchor.isEmpty() || byAnchor.size() * 2 < reply.size()) {
            return invalid(dropped.isEmpty() ? "no usable sections" : String.join("; ", dropped));
        }
        if (!dropped.isEmpty()) {
            log.info("AI section breakdown: ignored {} of {} entries ({})", dropped.size(), reply.size(),
                    String.join("; ", dropped));
        }
        List<ParsedSection> sections = new ArrayList<>();
        byAnchor.forEach((anchor, section) -> {
            String paragraphText = ((XWPFParagraph) elements.get(anchor)).getText().strip();
            String title = section.title() == null || section.title().isBlank()
                    ? paragraphText : section.title().strip();
            int level = section.headingLevel() == null ? 1
                    : Math.max(1, Math.min(9, section.headingLevel()));
            sections.add(new ParsedSection(sections.size(), truncate(title), level, anchor,
                    truncate(paragraphText), mergeKeywords(section.keywords(), title),
                    normalizeSectionKind(section.sectionKind())));
        });
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
