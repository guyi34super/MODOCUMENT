package com.modocument.infrastructure.transform.code;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Position-preserving PDF text. The default {@link PDFTextStripper} output
 * joins table cells with one space, which loses the columns; this reader
 * places every text chunk at a character column derived from its x position,
 * so cells that share an x line up and the gaps between them stay wide
 * enough for {@link FixedWidthStrategy}. Chunks closer than about two space
 * widths stay one space apart (words of one cell). Pages are separated by a
 * form-feed line.
 */
final class PdfLayoutText {

    static final int MAX_PAGES = 300;
    static final String PAGE_BREAK = "\f";

    private PdfLayoutText() {
    }

    static String read(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            LayoutStripper stripper = new LayoutStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(document.getNumberOfPages(), MAX_PAGES));
            stripper.getText(document);
            return stripper.render();
        }
    }

    private record Chunk(float xStart, float xEnd, String text, float spaceWidth) {
        float charWidth() {
            return text.isEmpty() ? 0 : (xEnd - xStart) / text.length();
        }
    }

    /** Collects positioned chunks per line instead of writing text. */
    private static final class LayoutStripper extends PDFTextStripper {

        private final List<List<Chunk>> lines = new ArrayList<>();
        private List<Chunk> current = new ArrayList<>();

        LayoutStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isEmpty() || positions == null || positions.isEmpty()) {
                return;
            }
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            current.add(new Chunk(first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(),
                    text, first.getWidthOfSpace()));
        }

        @Override
        protected void writeWordSeparator() {
            // Spacing is rebuilt from positions in render().
        }

        @Override
        protected void writeLineSeparator() {
            endLine();
        }

        @Override
        protected void writePageEnd() {
            endLine();
            lines.add(null);
        }

        private void endLine() {
            if (!current.isEmpty()) {
                lines.add(current);
                current = new ArrayList<>();
            }
        }

        String render() {
            endLine();
            List<Float> widths = new ArrayList<>();
            for (List<Chunk> line : lines) {
                if (line != null) {
                    for (Chunk chunk : line) {
                        if (chunk.text().trim().length() >= 2) {
                            widths.add(chunk.charWidth());
                        }
                    }
                }
            }
            float charWidth = median(widths, 5f);
            // Half a character per column: proportional text never overruns
            // the next cell's column, so aligned cells stay aligned.
            float unit = Math.max(1f, charWidth / 2f);
            StringBuilder out = new StringBuilder();
            for (List<Chunk> line : lines) {
                if (line == null) {
                    out.append(PAGE_BREAK).append('\n');
                    continue;
                }
                StringBuilder text = new StringBuilder();
                Chunk previous = null;
                for (Chunk chunk : line) {
                    int column = Math.round(chunk.xStart() / unit);
                    if (previous == null) {
                        pad(text, column);
                    } else {
                        float gap = chunk.xStart() - previous.xEnd();
                        float sameCell = Math.max(2f * previous.spaceWidth(), 0.8f * charWidth);
                        if (gap <= sameCell) {
                            if (gap > 0.15f * charWidth && text.charAt(text.length() - 1) != ' ') {
                                text.append(' ');
                            }
                        } else {
                            pad(text, Math.max(column, text.length() + 2));
                        }
                    }
                    text.append(chunk.text());
                    previous = chunk;
                }
                out.append(stripTrailing(text)).append('\n');
            }
            return out.toString();
        }

        private static void pad(StringBuilder text, int column) {
            while (text.length() < column) {
                text.append(' ');
            }
        }

        private static String stripTrailing(StringBuilder text) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == ' ') {
                end--;
            }
            return text.substring(0, end);
        }

        private static float median(List<Float> values, float fallback) {
            if (values.isEmpty()) {
                return fallback;
            }
            List<Float> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            return sorted.get(sorted.size() / 2);
        }
    }
}
