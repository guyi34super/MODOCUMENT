package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.util.ArrayList;
import java.util.List;

/** Auto-fit and word wrap of a value inside a box. */
final class TextLayout {

    static final float LINE_GAP = 1.15f;
    private static final float PAD = 1f;

    /** The result: font size, lines, and whether the text still did not fit at the minimum size. */
    record Fit(float size, List<String> lines, boolean overflow) {
    }

    private TextLayout() {
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n') {
                sb.append('\n');
            } else if (cp == '\r') {
                // dropped; \r\n becomes \n
            } else if (Character.isISOControl(cp) || cp == '­') {
                sb.append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    /**
     * Fits text into a box: starting at {@code startSize} (capped by the box height), shrinking in half-point
     * steps to {@code minSize}. Single-line boxes never wrap; multi-line boxes wrap on words up to
     * {@code maxLines} and the box height.
     */
    static Fit fit(PDFont font, String text, float boxW, float boxH, float startSize, float minSize,
                   int maxLines, boolean multiline) {
        float[] m = FontResolver.metrics(font);
        float em = m[0] - m[1];
        float width = Math.max(1, boxW - 2 * PAD);
        float size = startSize > 0 ? startSize : Math.min(12f, boxH / (em * 1.05f));
        float heightCap = boxH / em;
        if (!multiline) {
            size = Math.min(size, heightCap);
        }
        size = Math.max(size, minSize);
        size = (float) (Math.floor(size * 2) / 2.0);
        if (size < minSize) {
            size = minSize;
        }
        String clean = clean(text);
        while (true) {
            List<String> lines;
            boolean fits;
            if (multiline) {
                lines = wrap(font, clean, size, width);
                float needed = em * size + (lines.size() - 1) * size * LINE_GAP;
                int cap = maxLines > 0 ? maxLines : Integer.MAX_VALUE;
                fits = lines.size() <= cap && needed <= boxH + 0.01f && widest(font, lines, size) <= width + 0.01f;
            } else {
                String one = clean.replace('\n', ' ');
                lines = List.of(one);
                fits = FontResolver.width(font, one, size) <= width + 0.01f && em * size <= boxH + 0.5f;
            }
            if (fits) {
                return new Fit(size, lines, false);
            }
            if (size <= minSize + 0.001f) {
                return new Fit(minSize, lines, true);
            }
            size = Math.max(minSize, size - 0.5f);
        }
    }

    static float widest(PDFont font, List<String> lines, float size) {
        float w = 0;
        for (String l : lines) {
            w = Math.max(w, FontResolver.width(font, l, size));
        }
        return w;
    }

    /** Greedy word wrap; words longer than the width are broken by characters. */
    static List<String> wrap(PDFont font, String text, float size, float width) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            String[] words = para.trim().split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (FontResolver.width(font, candidate, size) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (FontResolver.width(font, word, size) <= width) {
                    line.append(word);
                    continue;
                }
                // break a long word
                StringBuilder part = new StringBuilder();
                for (int i = 0; i < word.length(); ) {
                    int cp = word.codePointAt(i);
                    String next = part + new String(Character.toChars(cp));
                    if (part.length() > 0 && FontResolver.width(font, next, size) > width) {
                        out.add(part.toString());
                        part.setLength(0);
                        part.appendCodePoint(cp);
                    } else {
                        part.setLength(0);
                        part.append(next);
                    }
                    i += Character.charCount(cp);
                }
                line.append(part);
            }
            out.add(line.toString());
        }
        // drop trailing empty lines
        while (out.size() > 1 && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
