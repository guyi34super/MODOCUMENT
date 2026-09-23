package com.modocument.doccreation.parse;

import org.apache.poi.xwpf.usermodel.IBodyElement;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaks an uploaded .docx blueprint into sections by its headings. A heading
 * is recognised by, in order of preference: the paragraph's (or its style's)
 * outline level — how Word itself marks Heading 1..9 — or a style id/name of
 * the form "Heading N"/"Title". When a document uses no heading styles at all,
 * a conservative fallback treats short, fully bold paragraphs as headings;
 * failing that, the whole document becomes one implicit section.
 */
@Component
public class BlueprintSectionParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^heading\\s*([1-9])$");
    private static final Pattern TITLE_STYLE = Pattern.compile("(?i)^title$");
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int FALLBACK_MAX_HEADING_LENGTH = 60;
    private static final Set<String> KEYWORD_STOPWORDS = Set.of(
            "the", "and", "of", "for", "a", "an", "to", "in", "on", "with", "report");

    /** Parses the stored blueprint file into ordered sections. */
    public List<ParsedSection> parse(Path docxPath, String fallbackTitle) {
        try (InputStream in = Files.newInputStream(docxPath);
             XWPFDocument doc = new XWPFDocument(in)) {
            List<ParsedSection> sections = scanHeadings(doc, this::styledHeadingLevel);
            if (sections.isEmpty()) {
                sections = scanHeadings(doc, BlueprintSectionParser::boldPseudoHeadingLevel);
                // A single bold line is more likely a stray emphasis than a
                // document structure; require at least two hits to trust it.
                if (sections.size() < 2) {
                    sections = List.of();
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
                sections.add(new ParsedSection(sections.size(), truncate(text), level, i,
                        truncate(text), seedKeywords(text)));
            }
        }
        return sections;
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

    /** Fallback for style-less documents: a short, fully bold line reads as a heading. */
    private static int boldPseudoHeadingLevel(XWPFDocument doc, XWPFParagraph paragraph) {
        String text = paragraph.getText().strip();
        if (text.length() > FALLBACK_MAX_HEADING_LENGTH
                || text.endsWith(".") || text.endsWith(":") || text.endsWith(";")) {
            return 0;
        }
        boolean sawText = false;
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText == null || runText.isBlank()) {
                continue;
            }
            sawText = true;
            if (!run.isBold()) {
                return 0;
            }
        }
        return sawText ? 1 : 0;
    }

    /**
     * Seeds a section's auto-assignment keywords from its title: lowercase
     * tokens minus stopwords and single characters, so "Roof Inspection – DC2"
     * yields roof, inspection, dc2.
     */
    static List<String> seedKeywords(String title) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : title.toLowerCase().split("[^a-z0-9]+")) {
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
