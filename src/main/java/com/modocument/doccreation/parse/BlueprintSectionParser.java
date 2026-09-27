package com.modocument.doccreation.parse;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPrBase;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaks a .docx blueprint of ANY kind — report, contract, policy, manual,
 * proposal, in any language — into sections. Word's own markers (outline
 * levels, Heading/Title styles) are trusted first; documents without them are
 * read by GENERIC emphasis signals rather than one known shape: fully bold
 * lines, ALL-CAPS lines, numbered clauses ("3.2 …", "(a) …"), underlined or
 * larger-font lines — each on a short, non-sentence line. Symbol prefixes
 * ("■ Roof:") and trailing colons are treated as decoration: they neither
 * qualify nor disqualify a heading, and titles are cleaned of them.
 */
@Component
public class BlueprintSectionParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^heading\\s*([1-9])$");
    private static final Pattern TITLE_STYLE = Pattern.compile("(?i)^title$");
    /** Dotted decimal ("3.", "3.2"), parenthesised letter/number ("(a)"), or roman ("IV."). */
    private static final Pattern NUMBERING_PREFIX = Pattern.compile(
            "^(?:(\\d{1,3}(?:\\.\\d{1,3})*)[.)]?|\\(([a-zA-Z]|\\d{1,3})\\)|([IVXLCivxlc]{1,6})[.)])\\s+\\S.*");
    /** Leading decoration: anything before the first letter or digit. */
    private static final Pattern LEADING_DECORATION = Pattern.compile("^[^\\p{L}\\p{Nd}]+");
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int FALLBACK_MAX_HEADING_LENGTH = 80;
    private static final Set<String> KEYWORD_STOPWORDS = Set.of(
            "the", "and", "of", "for", "a", "an", "to", "in", "on", "with", "report");

    /** Parses the stored blueprint file into ordered sections. */
    public List<ParsedSection> parse(Path docxPath, String fallbackTitle) {
        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            List<ParsedSection> styled = scanHeadings(doc, this::styledHeadingLevel);
            List<ParsedSection> sections = styled;
            // Word markers found little or nothing: read emphasis signals too.
            // Merging (not either/or) covers documents with a styled Title but
            // unstyled section labels.
            if (styled.size() < 2) {
                Integer bodyFontSize = modalFontSize(doc);
                List<ParsedSection> pseudo = scanHeadings(doc,
                        (d, p) -> pseudoHeadingLevel(p, bodyFontSize));
                List<ParsedSection> merged = merge(styled, pseudo);
                // A single emphasized line is more likely stray emphasis than
                // structure; only trust the signal scan when it corroborates.
                if (merged.size() >= 2) {
                    sections = merged;
                }
            }
            if (sections.isEmpty()) {
                String title = fallbackTitle == null || fallbackTitle.isBlank() ? "Document" : fallbackTitle;
                return List.of(new ParsedSection(0, truncate(title), 0, -1, null, seedKeywords(title)));
            }
            return sections;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the blueprint document.", e);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The uploaded file is not a readable .docx document.", e);
        }
    }

    private interface HeadingDetector {
        /** Returns the heading level 1..9, or 0 when the paragraph is not a heading. */
        int levelOf(XWPFDocument doc, XWPFParagraph paragraph);
    }

    private List<ParsedSection> scanHeadings(XWPFDocument doc, HeadingDetector detector) {
        List<ParsedSection> sections = new ArrayList<>();
        List<IBodyElement> elements = doc.getBodyElements();
        for (int i = 0; i < elements.size(); i++) {
            if (!(elements.get(i) instanceof XWPFParagraph paragraph)) {
                continue;
            }
            String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
            if (text.isEmpty()) {
                continue;
            }
            int level = detector.levelOf(doc, paragraph);
            if (level > 0) {
                String title = cleanTitle(text);
                sections.add(new ParsedSection(sections.size(), truncate(title), level, i,
                        truncate(text), seedKeywords(title)));
            }
        }
        return sections;
    }

    /** Both scans, deduplicated by anchor, in document order, renumbered. */
    private static List<ParsedSection> merge(List<ParsedSection> styled, List<ParsedSection> pseudo) {
        Map<Integer, ParsedSection> byAnchor = new TreeMap<>();
        for (ParsedSection section : pseudo) {
            byAnchor.put(section.anchorParagraphIndex(), section);
        }
        for (ParsedSection section : styled) {
            byAnchor.put(section.anchorParagraphIndex(), section); // styled wins on the same line
        }
        List<ParsedSection> merged = new ArrayList<>();
        for (ParsedSection section : byAnchor.values()) {
            merged.add(new ParsedSection(merged.size(), section.title(), section.headingLevel(),
                    section.anchorParagraphIndex(), section.anchorText(), section.keywords()));
        }
        return merged;
    }

    /** Outline level (Word's own heading marker), else Heading N / Title style names. */
    private int styledHeadingLevel(XWPFDocument doc, XWPFParagraph paragraph) {
        Integer direct = outlineLevel(directPPr(paragraph));
        if (direct != null) {
            return direct + 1;
        }
        String styleId = paragraph.getStyle();
        XWPFStyle style = null;
        XWPFStyles styles = doc.getStyles();
        if (styleId != null && styles != null) {
            style = styles.getStyle(styleId);
        }
        if (style != null) {
            Integer styled = outlineLevel(style.getCTStyle().getPPr());
            if (styled != null) {
                return styled + 1;
            }
        }
        int byName = headingLevelFromStyleName(styleId);
        if (byName == 0 && style != null) {
            byName = headingLevelFromStyleName(style.getName());
        }
        return byName;
    }

    private static CTPPrBase directPPr(XWPFParagraph paragraph) {
        // CTPPr extends CTPPrBase, which carries outlineLvl; the direct
        // paragraph properties and a style's properties share that shape.
        return paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : null;
    }

    private static Integer outlineLevel(CTPPrBase ppr) {
        if (ppr == null || !ppr.isSetOutlineLvl() || ppr.getOutlineLvl().getVal() == null) {
            return null;
        }
        int val = ppr.getOutlineLvl().getVal().intValue();
        // 9 = "body text" sentinel, not a heading.
        return val >= 0 && val <= 8 ? val : null;
    }

    private static int headingLevelFromStyleName(String name) {
        if (name == null) {
            return 0;
        }
        Matcher heading = HEADING_STYLE.matcher(name.strip());
        if (heading.matches()) {
            return Integer.parseInt(heading.group(1));
        }
        return TITLE_STYLE.matcher(name.strip()).matches() ? 1 : 0;
    }

    /**
     * Signal-based fallback for style-less documents. Gate: a short line that
     * doesn't end like a sentence. Any STRONG emphasis signal makes it a
     * heading — fully bold, ALL-CAPS, a numbering prefix, underlined, or a
     * font larger than the document's body — whatever convention the document
     * follows. A trailing ":" or a leading "■"-style glyph is decoration, not
     * a signal and not a disqualifier.
     */
    static int pseudoHeadingLevel(XWPFParagraph paragraph, Integer bodyFontSize) {
        String text = paragraph.getText().strip();
        if (text.length() > FALLBACK_MAX_HEADING_LENGTH
                || text.endsWith(".") || text.endsWith(";") || text.endsWith(",")) {
            return 0;
        }
        // A real Word list item (w:numPr) is content, not a heading — its
        // number is rendered by Word, unlike a hand-typed "3.2 Earthing".
        if (paragraph.getCTP().isSetPPr() && paragraph.getCTP().getPPr().isSetNumPr()) {
            return 0;
        }
        Matcher numbering = NUMBERING_PREFIX.matcher(cleanLeading(text));
        boolean numbered = numbering.matches();

        boolean sawText = false;
        boolean allBold = true;
        boolean allUnderlined = true;
        boolean larger = bodyFontSize != null;
        int letters = 0;
        boolean anyLowercase = false;
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText == null || runText.isBlank()) {
                continue;
            }
            sawText = true;
            allBold &= run.isBold();
            allUnderlined &= run.getUnderline() != null && run.getUnderline() != UnderlinePatterns.NONE;
            Double size = run.getFontSizeAsDouble();
            larger &= size != null && bodyFontSize != null && size > bodyFontSize;
            for (int i = 0; i < runText.length(); i++) {
                char c = runText.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    anyLowercase |= Character.isLowerCase(c);
                }
            }
        }
        if (!sawText) {
            return 0;
        }
        boolean allCaps = letters >= 3 && !anyLowercase;
        if (allBold || allCaps || numbered || allUnderlined || larger) {
            if (numbered && numbering.group(1) != null) {
                int depth = numbering.group(1).split("\\.").length;
                return Math.min(9, Math.max(1, depth));
            }
            return numbered && numbering.group(2) != null ? 2 : 1;
        }
        return 0;
    }

    /** The document's most common explicit run font size, or null when sizes are implicit. */
    static Integer modalFontSize(XWPFDocument doc) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (XWPFParagraph paragraph : doc.getParagraphs()) {
            for (XWPFRun run : paragraph.getRuns()) {
                String text = run.text();
                Double size = run.getFontSizeAsDouble();
                if (size != null && text != null && !text.isBlank()) {
                    counts.merge((int) Math.round(size), text.length(), Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Display title: leading bullet/symbol decoration and one trailing colon
     * stripped ("■ Client Information:" → "Client Information"). NEVER used
     * for anchor text — the anchor stays the document's raw line.
     */
    static String cleanTitle(String rawText) {
        String cleaned = cleanLeading(rawText);
        if (cleaned.endsWith(":")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).stripTrailing();
        }
        return cleaned.isBlank() ? rawText.strip() : cleaned;
    }

    private static String cleanLeading(String text) {
        return LEADING_DECORATION.matcher(text.strip()).replaceFirst("").strip();
    }

    /**
     * Seeds a section's auto-assignment keywords from its title: lowercase
     * tokens minus stopwords and single characters, so "Roof Inspection – DC2"
     * yields roof, inspection, dc2.
     */
    static List<String> seedKeywords(String title) {
        Set<String> keywords = new LinkedHashSet<>();
        // Locale.ROOT and a Unicode split: "Toiture Étanchéité" and Turkish
        // "INVERTER" must tokenize the same on every JVM locale.
        for (String token : title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (token.length() >= 2 && !KEYWORD_STOPWORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return List.copyOf(keywords);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }
}
