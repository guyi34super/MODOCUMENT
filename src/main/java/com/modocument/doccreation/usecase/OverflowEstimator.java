package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The builder's overflow warning: which values will not fit their box even
 * at the minimum font size, so generation will write "(continued on page N)"
 * and carry the text on an added page. An ESTIMATE with Helvetica metrics
 * (the generator measures with the region's real font): wide enough to warn
 * early, never used to change the output.
 */
public final class OverflowEstimator {

    /** Line height as a multiple of the font size. */
    private static final float LEADING = 1.2f;
    private static final PDType1Font METRICS = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private OverflowEstimator() {
    }

    /** The keys of the text regions whose value overflows at {@code minFontSize}, in region order. */
    public static List<String> overflowing(List<TemplateRegion> regions, Map<String, String> values,
                                           float minFontSize) {
        List<String> out = new ArrayList<>();
        for (TemplateRegion region : regions) {
            String value = values.get(region.key());
            if (value == null || value.isEmpty() || !isText(region.kind())) {
                continue;
            }
            if (overflows(region, value, minFontSize)) {
                out.add(region.key());
            }
        }
        return out;
    }

    static boolean overflows(TemplateRegion region, String value, float minFontSize) {
        float size = Math.max(1f, minFontSize);
        float width = region.box().w();
        int boxLines = Math.max(1, (int) Math.floor(region.box().h() / (size * LEADING)));
        if (region.kind() != RegionKind.MULTILINE) {
            return value.contains("\n") || textWidth(value, size) > width;
        }
        int allowed = region.maxLines() > 0 ? Math.min(region.maxLines(), boxLines) : boxLines;
        return wrappedLines(value, size, width) > allowed;
    }

    private static int wrappedLines(String value, float size, float width) {
        int lines = 0;
        for (String paragraph : value.split("\\r?\\n", -1)) {
            lines++;
            float line = 0;
            for (String word : paragraph.split(" ", -1)) {
                float w = textWidth(word, size);
                float space = line == 0 ? 0 : textWidth(" ", size);
                if (line > 0 && line + space + w > width) {
                    lines++;
                    line = w;
                } else {
                    line += space + w;
                }
                while (line > width && width > 0) {
                    // A word longer than the line breaks across lines.
                    lines++;
                    line -= width;
                }
            }
        }
        return lines;
    }

    private static float textWidth(String text, float size) {
        float units = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            units += glyphWidth(codePoint);
        }
        return units / 1000f * size;
    }

    private static float glyphWidth(int codePoint) {
        try {
            String glyph = new String(Character.toChars(codePoint));
            return METRICS.getStringWidth(glyph);
        } catch (IOException | IllegalArgumentException e) {
            // Not in WinAnsi (e.g. CJK): count it as a wide glyph.
            return 1000f;
        }
    }

    private static boolean isText(RegionKind kind) {
        return kind == RegionKind.TEXT || kind == RegionKind.MULTILINE || kind == RegionKind.DATE
                || kind == RegionKind.NUMBER;
    }
}
