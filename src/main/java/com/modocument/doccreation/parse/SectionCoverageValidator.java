package com.modocument.doccreation.parse;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** How much of a blueprint's body the section list spans (for template review). */
public final class SectionCoverageValidator {

    private SectionCoverageValidator() {
    }

    public record Gap(int fromIndex, int toIndex, String preview) {
    }

    public record Result(int coveragePercent, List<Gap> gaps, int meaningfulElements) {
        public static Result empty() {
            return new Result(100, List.of(), 0);
        }
    }

    /**
     * A body element counts if it is a table or a non-empty paragraph. Each
     * section owns from its anchor through the element before the next anchor.
     */
    public static Result compute(List<ParsedSection> sections, List<IBodyElement> elements) {
        if (elements.isEmpty()) {
            return Result.empty();
        }
        List<Integer> meaningful = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (isMeaningful(elements.get(i))) {
                meaningful.add(i);
            }
        }
        if (meaningful.isEmpty()) {
            return Result.empty();
        }
        if (sections == null || sections.isEmpty()) {
            return new Result(0, List.of(singleGap(meaningful, elements)), meaningful.size());
        }
        List<ParsedSection> ordered = sections.stream()
                .filter(s -> s.anchorParagraphIndex() >= 0)
                .sorted(Comparator.comparingInt(ParsedSection::anchorParagraphIndex))
                .toList();
        if (ordered.isEmpty()) {
            return new Result(0, List.of(singleGap(meaningful, elements)), meaningful.size());
        }
        boolean[] covered = new boolean[elements.size()];
        for (int s = 0; s < ordered.size(); s++) {
            int start = ordered.get(s).anchorParagraphIndex();
            int end = s + 1 < ordered.size()
                    ? ordered.get(s + 1).anchorParagraphIndex() : elements.size();
            for (int i = start; i < end && i < covered.length; i++) {
                covered[i] = true;
            }
        }
        List<Gap> gaps = new ArrayList<>();
        int coveredCount = 0;
        int gapStart = -1;
        for (int index : meaningful) {
            if (covered[index]) {
                coveredCount++;
                if (gapStart >= 0) {
                    gaps.add(buildGap(gapStart, index - 1, elements));
                    gapStart = -1;
                }
            } else if (gapStart < 0) {
                gapStart = index;
            }
        }
        if (gapStart >= 0) {
            int last = meaningful.get(meaningful.size() - 1);
            gaps.add(buildGap(gapStart, last, elements));
        }
        int percent = (int) Math.round(100.0 * coveredCount / meaningful.size());
        return new Result(Math.min(100, Math.max(0, percent)), gaps, meaningful.size());
    }

    /** Insert one section per gap at the gap's first index (for auto-fix after AI). */
    public static List<ParsedSection> gapFillSections(List<ParsedSection> sections, List<Gap> gaps,
                                                      List<IBodyElement> elements) {
        if (gaps.isEmpty()) {
            return sections;
        }
        List<ParsedSection> merged = new ArrayList<>(sections);
        for (Gap gap : gaps) {
            String title = titleForGap(gap, elements);
            merged.add(new ParsedSection(merged.size(), title, 2, gap.fromIndex(),
                    title, List.of(), ParsedSection.KIND_NARRATIVE));
        }
        merged.sort(Comparator.comparingInt(ParsedSection::anchorParagraphIndex));
        List<ParsedSection> reordered = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            ParsedSection s = merged.get(i);
            reordered.add(new ParsedSection(i, s.title(), s.headingLevel(), s.anchorParagraphIndex(),
                    s.anchorText(), s.keywords(), s.sectionKind()));
        }
        return reordered;
    }

    private static Gap singleGap(List<Integer> meaningful, List<IBodyElement> elements) {
        return buildGap(meaningful.get(0), meaningful.get(meaningful.size() - 1), elements);
    }

    private static Gap buildGap(int from, int to, List<IBodyElement> elements) {
        return new Gap(from, to, preview(elements, from));
    }

    private static String preview(List<IBodyElement> elements, int index) {
        if (index < 0 || index >= elements.size()) {
            return "";
        }
        if (elements.get(index) instanceof XWPFParagraph paragraph) {
            String text = paragraph.getText() == null ? "" : paragraph.getText().strip();
            return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        }
        return "<TABLE>";
    }

    private static String titleForGap(Gap gap, List<IBodyElement> elements) {
        String preview = preview(elements, gap.fromIndex());
        if (preview.isBlank()) {
            return "Section at ¶" + gap.fromIndex();
        }
        return preview.length() > 80 ? preview.substring(0, 80) : preview;
    }

    static boolean isMeaningful(IBodyElement element) {
        if (element instanceof XWPFTable) {
            return true;
        }
        if (element instanceof XWPFParagraph paragraph) {
            String text = paragraph.getText();
            return text != null && !text.isBlank();
        }
        return false;
    }
}
