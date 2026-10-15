package com.modocument.doccreation.pdftemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the sample data of a filled document: AcroForm values, the value next to or under a label, text in
 * boxes and table body cells, text on fill-in lines, checked boxes, photos in photo boxes, and (with low
 * confidence, for review) free-standing value shapes. Precision first: headings, labels, repeated page
 * furniture and sentences are never proposed with confidence 0.6 or more.
 */
final class SampleDataDetector {

    static final double REVIEW_THRESHOLD = 0.6;
    private static final Pattern INLINE_LABEL = Pattern.compile("(?<=\\s)(\\p{L}[\\p{L}\\d ./()'&-]{0,30}):(?=\\s|$)");

    /** A piece of a run: a label ("Name:"), a leader, or text. */
    static final class Piece {
        final TextRun run;
        final List<Glyph> glyphs;
        final String text;
        final boolean label;
        final boolean leader;
        final Rect layout;
        final Rect ink;

        Piece(TextRun run, List<Glyph> glyphs, String text, boolean label, boolean leader) {
            this.run = run;
            this.glyphs = glyphs;
            this.text = text.strip();
            this.label = label;
            this.leader = leader;
            this.layout = TextRun.layoutBox(glyphs);
            this.ink = TextRun.inkBox(glyphs);
        }

        @Override
        public String toString() {
            return (label ? "L" : leader ? "_" : "T") + "'" + text + "'";
        }
    }

    private static final class Value {
        final List<Piece> pieces = new ArrayList<>();
        String label;
        TextRun labelRun;
        double confidence;
        Candidate blank;
        PageModel.Cell cell;
        RegionKind kind;
    }

    private int counter;

    List<Candidate> detect(List<PageModel> pages, List<Candidate> blanks) {
        Set<String> repeated = repeatedTexts(pages);
        List<Candidate> out = new ArrayList<>();
        for (PageModel page : pages) {
            out.addAll(detectPage(page, pageBlanks(blanks, page.index()), repeated));
        }
        return out;
    }

    private static List<Candidate> pageBlanks(List<Candidate> blanks, int page) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : blanks) {
            if (c.page == page) {
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ per page

    private List<Candidate> detectPage(PageModel page, List<Candidate> blanks, Set<String> repeated) {
        List<List<Piece>> lines = new ArrayList<>();
        for (List<TextRun> line : page.lines) {
            List<Piece> pieces = new ArrayList<>();
            for (TextRun run : line) {
                pieces.addAll(split(run));
            }
            pieces.sort((a, b) -> Float.compare(a.layout.x0(), b.layout.x0()));
            lines.add(pieces);
        }
        Map<Piece, Value> assigned = new IdentityHashMap<>();
        List<Value> values = new ArrayList<>();
        Map<TextRun, Boolean> headings = new IdentityHashMap<>();
        for (TextRun r : page.runs) {
            headings.put(r, Labels.isHeading(r, page));
        }

        // AcroForm values: regions come from the widgets (cleared via clearAcroForm), nothing to cut here
        // 1. text sitting on fill-in lines and leaders
        for (Candidate b : blanks) {
            if (b.origin != Candidate.Origin.LEADER && b.origin != Candidate.Origin.LINE) {
                continue;
            }
            Rect zone = b.box.expand(1f, 0).union(new Rect(b.box.x0(), b.box.y0(), b.box.x1(), b.box.y1() + 3));
            Value v = null;
            for (List<Piece> line : lines) {
                for (Piece p : line) {
                    if (p.label || p.leader || assigned.containsKey(p) || p.ink == null) {
                        continue;
                    }
                    if (zone.contains(p.ink.cx(), p.ink.cy()) && p.layout.xOverlap(b.box) >= 0.6f * p.layout.w()) {
                        if (v == null) {
                            v = new Value();
                            v.label = b.label;
                            v.labelRun = b.labelRun;
                            v.blank = b;
                            v.confidence = 0.88;
                            values.add(v);
                        }
                        v.pieces.add(p);
                        assigned.put(p, v);
                    }
                }
            }
        }

        // 2. "Label: value" in one run, and "Label:" followed by text on the same line
        for (List<Piece> line : lines) {
            for (int i = 0; i < line.size(); i++) {
                Piece p = line.get(i);
                if (!p.label) {
                    continue;
                }
                int j = i + 1;
                while (j < line.size() && line.get(j).leader) {
                    j++;
                }
                if (j >= line.size()) {
                    continue;
                }
                Piece next = line.get(j);
                if (next.label || assigned.containsKey(next) || next.ink == null) {
                    continue;
                }
                boolean sameRun = next.run == p.run;
                float gap = next.layout.x0() - p.layout.x1();
                if (!sameRun && (gap > 250 || gap < -2)) {
                    continue;
                }
                if (!sameRun && Boolean.TRUE.equals(headings.get(next.run)) && !next.run.bold) {
                    continue;
                }
                if (!sameCellOrAdjacent(page, p, next) || crossesRule(page, p, next)) {
                    continue;
                }
                if (Labels.words(next.text) > 14 && page.cellAt(next.ink.cx(), next.ink.cy()) == null) {
                    continue;
                }
                Value v = new Value();
                v.label = Labels.clean(p.text);
                v.labelRun = p.run;
                v.confidence = sameRun ? 0.9 : 0.88;
                v.pieces.add(next);
                v.cell = page.cellAt(next.ink.cx(), next.ink.cy());
                assigned.put(next, v);
                values.add(v);
            }
        }

        // 3a. checked boxes
        for (Candidate b : blanks) {
            if (b.kind != RegionKind.CHECKBOX || b.origin == Candidate.Origin.ACRO) {
                continue;
            }
            for (List<Piece> line : lines) {
                for (Piece p : line) {
                    if (!assigned.containsKey(p) && p.ink != null && b.box.expand(2).contains(p.ink.cx(), p.ink.cy())
                            && Labels.CHECK_GLYPH.matcher(p.text).matches()) {
                        Value v = new Value();
                        v.label = b.label;
                        v.labelRun = b.labelRun;
                        v.blank = b;
                        v.kind = RegionKind.CHECKBOX;
                        v.confidence = 0.9;
                        v.pieces.add(p);
                        assigned.put(p, v);
                        values.add(v);
                    }
                }
            }
        }

        // 3. tables and boxes
        tables(page, lines, assigned, values, headings);

        // 4. label above, value lines below (addresses), only short lines
        for (int li = 0; li < lines.size(); li++) {
            for (Piece p : lines.get(li)) {
                if (!p.label || hasValueRight(lines.get(li), p, assigned)) {
                    continue;
                }
                PageModel.Cell cell = page.cellAt(p.ink.cx(), p.ink.cy());
                Value v = null;
                float prevBase = p.run.baseline;
                for (int lj = li + 1; lj < lines.size() && lj <= li + 8; lj++) {
                    Piece hit = null;
                    for (Piece q : lines.get(lj)) {
                        if (q.label || q.leader || assigned.containsKey(q) || q.ink == null) {
                            continue;
                        }
                        boolean aligned = q.layout.x0() >= p.layout.x0() - 4 && q.layout.x0() <= p.layout.x0() + 60;
                        boolean inCell = cell == null ? page.cellAt(q.ink.cx(), q.ink.cy()) == null
                                : cell.rect().contains(q.ink.cx(), q.ink.cy());
                        if (aligned && inCell) {
                            hit = q;
                            break;
                        }
                    }
                    if (hit == null || prevBase - hit.run.baseline > 1.9f * Math.max(hit.run.size, p.run.size)) {
                        break;
                    }
                    boolean sentence = Labels.words(hit.text) > 8 || hit.text.matches(".*\\w{3,}\\.$")
                            && Labels.words(hit.text) >= 5;
                    if ((sentence && cell == null) || Boolean.TRUE.equals(headings.get(hit.run))) {
                        break;
                    }
                    if (v == null) {
                        v = new Value();
                        v.label = Labels.clean(p.text);
                        v.labelRun = p.run;
                        v.confidence = cell != null ? 0.82 : 0.75;
                        v.cell = cell;
                        values.add(v);
                    }
                    v.pieces.add(hit);
                    assigned.put(hit, v);
                    prevBase = hit.run.baseline;
                }
            }
        }

        // 6. free-standing value shapes (review only)
        for (List<Piece> line : lines) {
            for (Piece p : line) {
                if (p.label || p.leader || assigned.containsKey(p) || p.ink == null || !Labels.isValueShape(p.text)) {
                    continue;
                }
                if (Boolean.TRUE.equals(headings.get(p.run))) {
                    continue;
                }
                Value v = new Value();
                TextRun left = BlankRegionDetector.labelLeft(page, p.layout, 120);
                v.label = left == null ? null : BlankRegionDetector.labelText(left, p.layout);
                v.labelRun = left;
                v.confidence = 0.55;
                v.pieces.add(p);
                v.cell = page.cellAt(p.ink.cx(), p.ink.cy());
                assigned.put(p, v);
                values.add(v);
            }
        }

        // vetoes and confidence adjustments
        for (Value v : values) {
            for (Piece p : v.pieces) {
                if (repeated.contains(repeatKey(p.run))) {
                    v.confidence = Math.min(v.confidence, 0.3);
                }
                if (pageFurniture(page, p)) {
                    v.confidence = Math.min(v.confidence, 0.45);
                }
            }
        }

        List<Candidate> out = new ArrayList<>();
        for (Value v : values) {
            Candidate c = toCandidate(page, v, assigned);
            if (c != null) {
                out.add(c);
            }
        }
        images(page, blanks, out);
        return out;
    }

    /**
     * Letterhead and footer text: in the top 12% or bottom 8% of the page and set apart from the body (smaller,
     * coloured, or next to a logo). Proposed only for review.
     */
    static boolean pageFurniture(PageModel page, Piece p) {
        boolean top = p.ink.y0() > page.height() * 0.88f;
        boolean bottom = p.ink.y1() < page.height() * 0.08f;
        if (!top && !bottom) {
            return false;
        }
        int rgb = p.run.rgb;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        boolean coloured = Math.max(r, Math.max(g, b)) > 60;
        boolean small = p.run.size < page.bodySize * 0.95f;
        boolean logo = false;
        for (Shapes.ImagePlacement img : page.images) {
            if (img.rect().yOverlap(p.ink.expand(20)) > 0 && img.rect().area() < 0.2f * page.width() * page.height()) {
                logo = true;
            }
        }
        return coloured || small || logo || bottom;
    }

    private static boolean hasValueRight(List<Piece> line, Piece label, Map<Piece, Value> assigned) {
        for (Piece q : line) {
            if (q.layout.x0() > label.layout.x1() - 1 && assigned.containsKey(q)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameCellOrAdjacent(PageModel page, Piece label, Piece value) {
        PageModel.Cell a = page.cellAt(label.ink.cx(), label.ink.cy());
        PageModel.Cell b = page.cellAt(value.ink.cx(), value.ink.cy());
        if (a == null && b == null || a == b) {
            return true;
        }
        if (a != null && b != null) {
            PageModel.Cell right = BlankRegionDetector.neighbour(page, a, 1);
            return right == b;
        }
        return false;
    }

    /** A vertical ruling between the label and the value that does not belong to a shared cell. */
    private static boolean crossesRule(PageModel page, Piece label, Piece value) {
        if (page.cellAt(label.ink.cx(), label.ink.cy()) != null) {
            return false;
        }
        for (Shapes.Segment s : page.segments) {
            if (!s.horizontal() && s.pos() > label.layout.x1() && s.pos() < value.layout.x0()
                    && s.from() < value.layout.cy() && s.to() > value.layout.cy()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ tables

    private void tables(PageModel page, List<List<Piece>> lines, Map<Piece, Value> assigned, List<Value> values,
                        Map<TextRun, Boolean> headings) {
        Map<Integer, List<PageModel.Cell>> byTable = new HashMap<>();
        for (PageModel.Cell c : page.cells) {
            byTable.computeIfAbsent(c.table(), k -> new ArrayList<>()).add(c);
        }
        for (List<PageModel.Cell> table : byTable.values()) {
            int rows = 0;
            int cols = 0;
            for (PageModel.Cell c : table) {
                rows = Math.max(rows, c.row() + 1);
                cols = Math.max(cols, c.col() + 1);
            }
            Map<PageModel.Cell, List<Piece>> inCell = new IdentityHashMap<>();
            for (List<Piece> line : lines) {
                for (Piece p : line) {
                    if (p.ink == null) {
                        continue;
                    }
                    for (PageModel.Cell c : table) {
                        if (c.rect().contains(p.ink.cx(), p.ink.cy())) {
                            PageModel.Cell smallest = page.cellAt(p.ink.cx(), p.ink.cy());
                            if (smallest == c) {
                                inCell.computeIfAbsent(c, k -> new ArrayList<>()).add(p);
                            }
                        }
                    }
                }
            }
            if (table.size() == 1) {
                box(page, table.get(0), inCell.getOrDefault(table.get(0), List.of()), assigned, values);
                continue;
            }
            if (rows < 2) {
                continue;
            }
            boolean headerRow = true;
            for (PageModel.Cell c : table) {
                if (c.row() != 0) {
                    continue;
                }
                List<Piece> ps = inCell.get(c);
                String t = ps == null ? "" : joined(ps);
                if (t.isEmpty() || Labels.words(t) > 5 || Labels.isValueShape(t)) {
                    headerRow = false;
                }
            }
            boolean labelColumn = cols >= 2;
            for (PageModel.Cell c : table) {
                if (c.col() != 0) {
                    continue;
                }
                List<Piece> ps = inCell.get(c);
                String t = ps == null ? "" : joined(ps);
                if (t.isEmpty() || !Labels.looksLikeLabel(t)) {
                    labelColumn = false;
                }
            }
            for (PageModel.Cell c : table) {
                List<Piece> ps = inCell.get(c);
                if (ps == null || ps.isEmpty()) {
                    continue;
                }
                String label = null;
                TextRun labelRun = null;
                double conf;
                if (headerRow && c.row() > 0) {
                    PageModel.Cell header = BlankRegionDetector.headerAbove(page, c);
                    if (header == null || inCell.get(header) == null) {
                        continue;
                    }
                    label = Labels.clean(joined(inCell.get(header))) + " " + (c.row() - header.row());
                    labelRun = inCell.get(header).get(0).run;
                    conf = 0.8;
                } else if (!headerRow && labelColumn && c.col() > 0) {
                    PageModel.Cell left = null;
                    for (PageModel.Cell o : table) {
                        if (o.col() == 0 && o.rect().yOverlap(c.rect()) > 0.5f * c.rect().h()) {
                            left = o;
                        }
                    }
                    if (left == null || inCell.get(left) == null) {
                        continue;
                    }
                    label = Labels.clean(joined(inCell.get(left)));
                    labelRun = inCell.get(left).get(0).run;
                    conf = 0.85;
                } else {
                    continue;
                }
                List<Piece> free = new ArrayList<>();
                for (Piece p : ps) {
                    if (!assigned.containsKey(p) && !p.label && !p.leader) {
                        free.add(p);
                    }
                }
                if (free.isEmpty()) {
                    continue;
                }
                String text = joined(free);
                if (Labels.isStaticCellWord(text) || text.endsWith(":")) {
                    continue;
                }
                boolean heading = free.stream().allMatch(p -> Boolean.TRUE.equals(headings.get(p.run))
                        && p.run.size >= page.bodySize * 1.18f);
                if (heading) {
                    continue;
                }
                if (Labels.isValueShape(text)) {
                    conf = Math.min(0.92, conf + 0.07);
                }
                Value v = new Value();
                v.label = label;
                v.labelRun = labelRun;
                v.confidence = conf;
                v.cell = c;
                for (Piece p : free) {
                    v.pieces.add(p);
                    assigned.put(p, v);
                }
                values.add(v);
            }
        }
    }

    /** Text inside a stand-alone box labelled from outside is the box's value. */
    private void box(PageModel page, PageModel.Cell cell, List<Piece> ps, Map<Piece, Value> assigned,
                     List<Value> values) {
        Rect r = cell.rect();
        if (ps.isEmpty() || r.w() > 0.9f * page.width() && r.h() > 0.4f * page.height() || r.w() <= 20 && r.h() <= 20) {
            return;
        }
        List<Piece> free = new ArrayList<>();
        for (Piece p : ps) {
            if (!assigned.containsKey(p) && !p.label && !p.leader) {
                free.add(p);
            }
        }
        if (free.isEmpty() || free.size() != ps.size()) {
            return;
        }
        String text = joined(free);
        if (Labels.PHOTO.matcher(text).find() || Labels.SIGN.matcher(text).find() && Labels.words(text) <= 4) {
            return;
        }
        TextRun labelRun = BlankRegionDetector.labelLeft(page, r, 160);
        if (labelRun == null) {
            labelRun = BlankRegionDetector.labelAbove(page, r);
        }
        if (labelRun == null || r.contains(labelRun.box.cx(), labelRun.box.cy())) {
            return;
        }
        Value v = new Value();
        v.label = BlankRegionDetector.labelText(labelRun, r);
        v.labelRun = labelRun;
        v.confidence = 0.85;
        v.cell = cell;
        for (Piece p : free) {
            v.pieces.add(p);
            assigned.put(p, v);
        }
        values.add(v);
    }

    private static String joined(List<Piece> ps) {
        StringBuilder sb = new StringBuilder();
        for (Piece p : ps) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.text);
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ photos

    private void images(PageModel page, List<Candidate> blanks, List<Candidate> out) {
        for (Candidate b : blanks) {
            if (b.kind != RegionKind.IMAGE && b.kind != RegionKind.SIGNATURE) {
                continue;
            }
            for (Shapes.ImagePlacement img : page.images) {
                if (!b.box.expand(3f).containsRect(img.rect(), 0.5f)) {
                    continue;
                }
                Candidate c = new Candidate(page.index(), Candidate.Origin.DATA, b.kind, b.box);
                c.id = "d" + (counter++);
                c.label = b.label;
                c.labelRun = b.labelRun;
                c.confidence = 0.85;
                c.source = RegionSource.DATA_REMOVED;
                c.maxLines = 0;
                c.sample = "[image]";
                c.imageData = true;
                c.removeBox = img.rect().expand(1f);
                c.mergedBlank = b;
                c.fontName = "LiberationSans";
                c.fontSize = 10;
                out.add(c);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ splitting runs

    static List<Piece> split(TextRun run) {
        String text = run.text;
        List<Piece> out = new ArrayList<>();
        int colon = labelColon(text, 0);
        if (colon < 0) {
            out.addAll(leaderSplit(run, 0, text.length(), text.strip().endsWith(":")
                    && Labels.looksLikeLabel(text)));
            return out;
        }
        int pos = 0;
        while (colon >= 0) {
            out.add(new Piece(run, run.glyphsFor(pos, colon + 1), text.substring(pos, colon + 1), true, false));
            int next = nextInlineLabel(text, colon + 1);
            int end = next < 0 ? text.length() : next;
            out.addAll(leaderSplit(run, colon + 1, end, false));
            pos = end;
            colon = next < 0 ? -1 : text.indexOf(':', next);
        }
        out.removeIf(p -> p.glyphs.isEmpty() || p.text.isEmpty());
        return out;
    }

    /** Text and leaders of text[from, to) as separate pieces. */
    private static List<Piece> leaderSplit(TextRun run, int from, int to, boolean label) {
        List<Piece> out = new ArrayList<>();
        Matcher m = Labels.LEADER.matcher(run.text);
        m.region(from, to);
        int pos = from;
        while (m.find()) {
            addPiece(out, run, pos, m.start(), label);
            addLeader(out, run, m.start(), m.end());
            pos = m.end();
        }
        addPiece(out, run, pos, to, label && pos == from);
        return out;
    }

    private static void addPiece(List<Piece> out, TextRun run, int from, int to, boolean label) {
        if (to <= from || run.text.substring(from, to).isBlank()) {
            return;
        }
        List<Glyph> g = run.glyphsFor(from, to);
        if (!g.isEmpty()) {
            out.add(new Piece(run, g, run.text.substring(from, to), label, false));
        }
    }

    private static void addLeader(List<Piece> out, TextRun run, int from, int to) {
        List<Glyph> g = run.glyphsFor(from, to);
        if (!g.isEmpty()) {
            out.add(new Piece(run, g, run.text.substring(from, to), false, true));
        }
    }

    /** Index of the colon ending a label at the start of text[from..], or -1. */
    static int labelColon(String text, int from) {
        int i = text.indexOf(':', from);
        while (i >= 0) {
            boolean time = i > 0 && i + 1 < text.length() && Character.isDigit(text.charAt(i - 1))
                    && Character.isDigit(text.charAt(i + 1));
            boolean url = text.startsWith("//", i + 1);
            if (!time && !url) {
                String prefix = text.substring(from, i + 1);
                return Labels.looksLikeLabel(prefix) ? i : -1;
            }
            i = text.indexOf(':', i + 1);
        }
        return -1;
    }

    private static int nextInlineLabel(String text, int from) {
        Matcher m = INLINE_LABEL.matcher(text);
        m.region(from, text.length());
        while (m.find()) {
            String label = m.group(1);
            if (Labels.looksLikeLabel(label) && Character.isUpperCase(label.codePointAt(0))) {
                int start = m.start(1);
                if (text.substring(from, start).isBlank()) {
                    continue;
                }
                return start;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ repeated page furniture

    private static String repeatKey(TextRun run) {
        return Math.round(run.box.x0() / 2) + ":" + Math.round(run.baseline / 2) + ":" + run.trimmed();
    }

    /** Runs at the same place with the same text on two or more pages are template, not data. */
    private static Set<String> repeatedTexts(List<PageModel> pages) {
        if (pages.size() < 2) {
            return Set.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (PageModel p : pages) {
            Set<String> seen = new java.util.HashSet<>();
            for (TextRun r : p.runs) {
                if (seen.add(repeatKey(r))) {
                    counts.merge(repeatKey(r), 1, Integer::sum);
                }
            }
        }
        Set<String> out = new java.util.HashSet<>();
        counts.forEach((k, v) -> {
            if (v >= 2) {
                out.add(k);
            }
        });
        return Collections.unmodifiableSet(out);
    }

    /** A data candidate for a whole run (the AI pointed at it); geometry from the run's real glyphs. */
    Candidate fromRun(PageModel page, TextRun run, TextRun labelRun, double confidence) {
        Value v = new Value();
        for (Piece p : split(run)) {
            if (!p.label && !p.leader) {
                v.pieces.add(p);
            }
        }
        if (v.pieces.isEmpty()) {
            return null;
        }
        v.label = labelRun == null ? null : BlankRegionDetector.labelText(labelRun, run.box);
        v.labelRun = labelRun;
        v.confidence = confidence;
        Piece first = v.pieces.get(0);
        v.cell = page.cellAt(first.ink.cx(), first.ink.cy());
        Candidate c = toCandidate(page, v, new IdentityHashMap<>());
        if (c != null) {
            c.confidence = Math.min(c.confidence, confidence);
        }
        return c;
    }

    // ------------------------------------------------------------------ to candidates

    private Candidate toCandidate(PageModel page, Value v, Map<Piece, Value> assigned) {
        List<Glyph> glyphs = new ArrayList<>();
        for (Piece p : v.pieces) {
            for (Glyph g : p.glyphs) {
                if (!g.isSpace()) {
                    glyphs.add(g);
                }
            }
        }
        if (glyphs.isEmpty()) {
            return null;
        }
        glyphs.sort((a, b) -> {
            int line = Float.compare(b.dOriginY, a.dOriginY);
            if (Math.abs(a.dOriginY - b.dOriginY) > 0.5f * Math.max(a.size, b.size)) {
                return line;
            }
            return Float.compare(a.dbox.x0(), b.dbox.x0());
        });
        // keep the spaces between words so the removed text reads naturally
        List<Glyph> all = new ArrayList<>();
        for (Piece p : v.pieces) {
            all.addAll(p.glyphs);
        }
        Rect layout = TextRun.layoutBox(glyphs);
        Rect ink = TextRun.inkBox(glyphs);
        Glyph first = glyphs.get(0);
        String text = sampleText(v.pieces);
        int lineCount = (int) text.lines().count();

        Candidate c = new Candidate(page.index(), Candidate.Origin.DATA, RegionKind.TEXT, layout);
        c.id = "d" + (counter++);
        c.valueGlyphs = all;
        c.sample = text;
        c.label = v.label == null || v.label.isBlank() ? null : v.label;
        c.labelRun = v.labelRun;
        c.confidence = v.confidence;
        c.source = RegionSource.DATA_REMOVED;
        c.fontName = first.ocr ? "LiberationSans" : first.fontName;
        c.fontSize = Math.round(first.size * 10f) / 10f;
        c.rgb = first.rgb;
        c.mergedBlank = v.blank;
        c.imageData = first.ocr;

        // what the field may occupy
        Rect field;
        if (v.blank != null && v.blank.kind != RegionKind.CHECKBOX) {
            field = new Rect(Math.min(v.blank.box.x0(), layout.x0()), layout.y0(),
                    Math.max(v.blank.box.x1(), layout.x1()), layout.y1());
        } else if (v.blank != null) {
            field = v.blank.box;
        } else if (v.cell != null) {
            Rect cr = v.cell.rect();
            field = new Rect(cr.x0() + 1.5f, Math.max(cr.y0() + 1f, layout.y0()), cr.x1() - 1.5f,
                    Math.min(cr.y1() - 1f, layout.y1()));
            float leftGap = layout.x0() - cr.x0();
            float rightGap = cr.x1() - layout.x1();
            if (rightGap < 6 && leftGap > 12) {
                c.align = Align.RIGHT;
            } else if (Math.abs(leftGap - rightGap) < 4 && leftGap > 8) {
                c.align = Align.CENTER;
            }
        } else {
            field = new Rect(layout.x0(), layout.y0(), extendRight(page, layout, v, assigned), layout.y1());
        }
        c.box = field;

        RegionKind kind = v.kind;
        if (kind == null) {
            kind = c.label == null ? RegionKind.TEXT : Labels.kindFor(c.label, RegionKind.TEXT);
            if (kind == RegionKind.IMAGE || kind == RegionKind.SIGNATURE) {
                kind = RegionKind.TEXT;
            }
            RegionKind byValue = Labels.kindForValue(text);
            if (byValue != null) {
                kind = byValue;
            } else if (kind == RegionKind.DATE || kind == RegionKind.NUMBER) {
                kind = RegionKind.TEXT;
            }
            if (lineCount > 1) {
                kind = RegionKind.MULTILINE;
            }
        }
        c.kind = kind;
        c.maxLines = switch (kind) {
            case MULTILINE -> Math.max(lineCount, Math.max(2, (int) (field.h() / (first.size * TextLayout.LINE_GAP))));
            case CHECKBOX, IMAGE, SIGNATURE -> 0;
            default -> 1;
        };
        if (kind == RegionKind.CHECKBOX) {
            c.sample = "true";
        }
        if (c.label == null) {
            c.label = kind == RegionKind.DATE ? "Date" : kind == RegionKind.NUMBER ? "Number" : "Field";
            c.confidence = Math.min(c.confidence, 0.55);
        }
        c.removeBox = removeBox(page, ink, all, first.ocr ? 2f : 1f);
        if (c.removeBox == null) {
            c.removeBox = ink;
            c.confidence = Math.min(c.confidence, 0.5);
        }
        return c;
    }

    private static String sampleText(List<Piece> pieces) {
        StringBuilder sb = new StringBuilder();
        Piece prev = null;
        for (Piece p : pieces) {
            if (prev != null) {
                boolean newLine = Math.abs(prev.run.baseline - p.run.baseline) > 0.5f * Math.max(p.run.size, prev.run.size);
                sb.append(newLine ? "\n" : " ");
            }
            sb.append(p.text);
            prev = p;
        }
        return sb.toString().strip();
    }

    /** How far right the field may extend: up to the next text, a vertical rule, or the text margin. */
    private static float extendRight(PageModel page, Rect value, Value v, Map<Piece, Value> assigned) {
        float margin = 0;
        for (TextRun r : page.runs) {
            margin = Math.max(margin, r.box.x1());
        }
        margin = Math.max(margin, page.width() - 50);
        float limit = Math.min(margin, value.x1() + 300);
        for (TextRun r : page.runs) {
            boolean mine = false;
            for (Piece p : v.pieces) {
                if (p.run == r) {
                    mine = true;
                    break;
                }
            }
            if (mine || r.box.x0() < value.x1() - 1 || r.box.yOverlap(value) < 0.3f * value.h()) {
                continue;
            }
            limit = Math.min(limit, r.box.x0() - 4);
        }
        for (Shapes.Segment s : page.segments) {
            if (!s.horizontal() && s.pos() > value.x1() && s.pos() < limit && s.from() < value.cy() && s.to() > value.cy()) {
                limit = s.pos() - 2;
            }
        }
        return Math.max(limit, value.x1() + 1);
    }

    /** Ink box plus padding, shrunk so that no other glyph's centre falls inside. Null when impossible. */
    static Rect removeBox(PageModel page, Rect ink, List<Glyph> mine, float pad) {
        Set<Glyph> own = Collections.newSetFromMap(new IdentityHashMap<>());
        own.addAll(mine);
        float x0 = ink.x0() - pad;
        float y0 = ink.y0() - pad;
        float x1 = ink.x1() + pad;
        float y1 = ink.y1() + pad;
        for (Glyph g : page.glyphs) {
            if (own.contains(g) || g.dbox == null) {
                continue;
            }
            Rect gr = g.dink != null ? g.dink : g.dbox;
            float cx = gr.cx();
            float cy = gr.cy();
            if (cx <= x0 || cx >= x1 || cy <= y0 || cy >= y1) {
                continue;
            }
            if (g.isSpace() && g.ocr) {
                continue;
            }
            if (ink.contains(cx, cy)) {
                if (g.isSpace()) {
                    continue;
                }
                return null;
            }
            // move the nearest side inward past the foreign centre
            float dl = cx - x0;
            float dr = x1 - cx;
            float db = cy - y0;
            float dt = y1 - cy;
            float min = Math.min(Math.min(dl, dr), Math.min(db, dt));
            if (min == dl && cx < ink.x0()) {
                x0 = cx + 0.01f;
            } else if (min == dr && cx > ink.x1()) {
                x1 = cx - 0.01f;
            } else if (cy < ink.y0()) {
                y0 = cy + 0.01f;
            } else if (cy > ink.y1()) {
                y1 = cy - 0.01f;
            } else if (cx < ink.x0()) {
                x0 = cx + 0.01f;
            } else {
                x1 = cx - 0.01f;
            }
        }
        return new Rect(x0, y0, x1, y1);
    }
}
