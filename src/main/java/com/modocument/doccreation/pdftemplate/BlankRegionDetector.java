package com.modocument.doccreation.pdftemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Finds fill-in areas from geometry alone: AcroForm widgets, underscore/dot leaders, fill-in lines, empty
 * boxes and table cells, the space right of a "Label:", checkbox squares, and photo/signature boxes.
 * Everything here is code; it always runs.
 */
final class BlankRegionDetector {

    private int counter;

    List<Candidate> detect(List<PageModel> pages) {
        List<Candidate> out = new ArrayList<>();
        for (PageModel page : pages) {
            List<Candidate> pageOut = new ArrayList<>();
            widgets(page, pageOut);
            leaders(page, pageOut);
            cells(page, pageOut);
            lines(page, pageOut);
            labelSpace(page, pageOut);
            out.addAll(pageOut);
        }
        return out;
    }

    private Candidate add(List<Candidate> out, PageModel page, Candidate.Origin origin, RegionKind kind, Rect box,
                          String label, TextRun labelRun, double confidence) {
        Candidate c = new Candidate(page.index(), origin, kind, box);
        c.id = "c" + (counter++);
        c.label = label;
        c.labelRun = labelRun;
        c.confidence = confidence;
        c.source = origin == Candidate.Origin.ACRO ? RegionSource.ACROFORM : RegionSource.DETECTED;
        if (labelRun != null) {
            c.required = Labels.isRequiredMark(labelRun.text);
            c.fontSize = Math.max(6f, Math.min(14f, labelRun.size));
            c.fontName = labelRun.bold ? FontResolver.bundledNameFor(labelRun.fontName).replaceAll("-Bold(Italic)?$", "")
                    : labelRun.fontName;
        } else {
            c.fontSize = Math.max(6f, Math.min(12f, page.bodySize));
            c.fontName = "LiberationSans";
        }
        c.rgb = 0;
        if (kind == RegionKind.CHECKBOX || kind == RegionKind.IMAGE || kind == RegionKind.SIGNATURE) {
            c.maxLines = 0;
        }
        out.add(c);
        return c;
    }

    // ------------------------------------------------------------------ AcroForm

    private void widgets(PageModel page, List<Candidate> out) {
        for (PageModel.Widget w : page.widgets) {
            RegionKind kind = switch (w.type()) {
                case "CHECKBOX", "RADIO" -> RegionKind.CHECKBOX;
                case "SIGNATURE" -> RegionKind.SIGNATURE;
                case "MULTILINE" -> RegionKind.MULTILINE;
                default -> RegionKind.TEXT;
            };
            String label = w.alternateName();
            TextRun labelRun = null;
            if (label == null || label.isBlank()) {
                labelRun = kind == RegionKind.CHECKBOX ? labelRight(page, w.rect()) : null;
                if (labelRun == null) {
                    labelRun = labelLeft(page, w.rect(), 220);
                }
                if (labelRun == null) {
                    labelRun = labelAbove(page, w.rect());
                }
                label = labelRun != null ? labelText(labelRun, w.rect()) : humanize(w.partialName());
            }
            if (kind == RegionKind.TEXT) {
                kind = Labels.kindFor(label, RegionKind.TEXT);
                if (kind == RegionKind.IMAGE) {
                    kind = RegionKind.TEXT;
                }
            }
            Candidate c = add(out, page, Candidate.Origin.ACRO, kind, w.rect(), label, labelRun, 1.0);
            c.acroName = w.fieldName();
            c.fontName = w.fontName();
            c.fontSize = w.fontSize();
            c.rgb = w.rgb();
            c.align = w.align();
            c.required = w.required();
            c.maxLines = kind == RegionKind.MULTILINE
                    ? Math.max(2, (int) (w.rect().h() / ((w.fontSize() > 0 ? w.fontSize() : 10f) * TextLayout.LINE_GAP)))
                    : kind == RegionKind.CHECKBOX || kind == RegionKind.SIGNATURE ? 0 : 1;
            if (w.value() != null && !w.value().isBlank()) {
                c.sample = w.value();
            }
        }
    }

    /** "Chapter ........ 12": a dot leader running into a page number is a contents line, not a field. */
    private static boolean tableOfContents(PageModel page, TextRun run, int leaderEnd) {
        String after = run.text.substring(leaderEnd).strip();
        if (after.matches("\\d{1,4}|[ivxlcIVXLC]{1,6}")) {
            return true;
        }
        if (!after.isEmpty()) {
            return false;
        }
        List<TextRun> line = page.lineOf(run);
        int i = line.indexOf(run);
        return i >= 0 && i + 1 < line.size() && line.get(i + 1).trimmed().matches("\\d{1,4}|[ivxlcIVXLC]{1,6}")
                && line.get(i + 1).box.x0() - run.box.x1() < 30;
    }

    static String humanize(String name) {
        if (name == null || name.isBlank()) {
            return "Field";
        }
        String s = name.replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[_.\\-]+", " ").strip();
        return s.isEmpty() ? "Field" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ leaders

    private void leaders(PageModel page, List<Candidate> out) {
        for (TextRun run : page.runs) {
            Matcher m = Labels.LEADER.matcher(run.text);
            int prevEnd = 0;
            while (m.find()) {
                List<Glyph> leader = run.glyphsFor(m.start(), m.end());
                if (leader.isEmpty() || tableOfContents(page, run, m.end())) {
                    continue;
                }
                Rect lb = TextRun.layoutBox(leader);
                float size = leader.get(0).size;
                float base = leader.get(0).dOriginY;
                Rect box = new Rect(lb.x0(), base - 0.2f * size, lb.x1(), base + 0.95f * size);
                String before = run.text.substring(prevEnd, m.start());
                prevEnd = m.end();
                String label = Labels.clean(before);
                TextRun labelRun = run;
                if (label.isEmpty()) {
                    labelRun = labelLeft(page, box, 160);
                    if (labelRun == null) {
                        labelRun = labelBelow(page, box);
                    }
                    if (labelRun == null) {
                        labelRun = labelAbove(page, box);
                    }
                    label = labelRun == null ? "" : labelText(labelRun, box);
                }
                double conf = label.isEmpty() ? 0.5 : 0.9;
                RegionKind kind = Labels.kindFor(label, RegionKind.TEXT);
                if (kind == RegionKind.IMAGE) {
                    kind = RegionKind.TEXT;
                }
                add(out, page, Candidate.Origin.LEADER, kind, box, label.isEmpty() ? "Field" : label,
                        labelRun, conf);
            }
        }
    }

    // ------------------------------------------------------------------ lines

    private void lines(PageModel page, List<Candidate> out) {
        for (Shapes.Segment s : page.freeLines) {
            if (s.length() < 30) {
                continue;
            }
            Rect lineRect = new Rect(s.from(), s.pos(), s.to(), s.pos());
            if (isUnderline(page, s)) {
                continue;
            }
            float room = 16f;
            for (TextRun r : page.runs) {
                if (r.box.xOverlap(lineRect) > 1 && r.box.y0() > s.pos() + 2) {
                    room = Math.min(room, r.box.y0() - s.pos() - 1);
                }
            }
            for (Shapes.Segment o : page.segments) {
                if (o.horizontal() && o.pos() > s.pos() + 2 && Math.min(o.to(), s.to()) - Math.max(o.from(), s.from()) > 1) {
                    room = Math.min(room, o.pos() - s.pos() - 1);
                }
            }
            if (room < 6) {
                continue;
            }
            Rect box = new Rect(s.from(), s.pos() + 0.5f, s.to(), s.pos() + room);
            if (overlapsExisting(out, page.index(), box, 0.3)) {
                continue;
            }
            TextRun labelRun = labelLeft(page, new Rect(s.from(), s.pos(), s.to(), s.pos() + 12), 160);
            boolean wide = s.length() > 0.5f * page.width();
            if (labelRun == null && wide) {
                continue;
            }
            if (labelRun == null) {
                labelRun = labelBelow(page, box);
            }
            if (labelRun == null) {
                labelRun = labelAbove(page, box);
            }
            String label = labelRun == null ? "Field" : labelText(labelRun, box);
            RegionKind kind = Labels.kindFor(label, RegionKind.TEXT);
            if (kind == RegionKind.IMAGE) {
                kind = RegionKind.TEXT;
            }
            add(out, page, Candidate.Origin.LINE, kind, box, label, labelRun, labelRun == null ? 0.45 : 0.8);
        }
    }

    /** A line drawn under text of exactly its width (decoration, not a fill-in line). */
    private static boolean isUnderline(PageModel page, Shapes.Segment s) {
        for (TextRun r : page.runs) {
            boolean sits = r.box.y0() >= s.pos() - 3 && r.box.y0() <= s.pos() + 4;
            if (sits && Math.abs(r.box.x0() - s.from()) < 2.5f && Math.abs(r.box.x1() - s.to()) < 2.5f) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ cells and boxes

    private void cells(PageModel page, List<Candidate> out) {
        for (PageModel.Cell cell : page.cells) {
            Rect r = cell.rect();
            if (r.w() > 0.9f * page.width() && r.h() > 0.4f * page.height()) {
                continue;
            }
            List<TextRun> inside = page.runsIn(r);
            boolean hasImage = page.images.stream().anyMatch(i -> r.containsRect(i.rect(), 1f));
            boolean square = r.w() >= 5 && r.w() <= 20 && r.h() >= 5 && r.h() <= 20 && Math.abs(r.w() - r.h()) <= 3;
            if (square) {
                boolean onlyCheck = inside.isEmpty() || inside.size() == 1
                        && Labels.CHECK_GLYPH.matcher(inside.get(0).trimmed()).matches();
                if (onlyCheck) {
                    TextRun labelRun = labelRight(page, r);
                    if (labelRun == null) {
                        labelRun = labelLeft(page, r, 120);
                    }
                    String label = labelRun == null ? "Checkbox" : labelText(labelRun, r);
                    add(out, page, Candidate.Origin.CHECKBOX, RegionKind.CHECKBOX, r.expand(-1f), label, labelRun,
                            labelRun == null ? 0.6 : 0.85);
                }
                continue;
            }
            if (r.w() < 12 || r.h() < 7) {
                continue;
            }
            // photo / logo / signature boxes with an instruction inside
            String insideText = String.join(" ", inside.stream().map(TextRun::trimmed).toList());
            if (!inside.isEmpty() && r.w() >= 36 && r.h() >= 36 && Labels.words(insideText) <= 8) {
                boolean photo = Labels.PHOTO.matcher(insideText).find();
                boolean sign = Labels.SIGN.matcher(insideText).find();
                if (photo || sign) {
                    String label = photo ? photoLabel(insideText) : "Signature";
                    add(out, page, Candidate.Origin.IMAGE_BOX, photo ? RegionKind.IMAGE : RegionKind.SIGNATURE,
                            r.expand(-1.5f), label, inside.get(0), 0.85);
                    continue;
                }
            }
            if (inside.isEmpty() && !hasImage) {
                emptyCell(page, cell, out);
            } else if (!inside.isEmpty()) {
                labelledCell(page, cell, inside, out);
            } else {
                // a box holding only an image: a photo box when labelled so
                TextRun labelRun = labelAbove(page, r);
                if (labelRun == null) {
                    labelRun = labelLeft(page, r, 160);
                }
                if (labelRun != null && Labels.PHOTO.matcher(labelRun.text).find()) {
                    add(out, page, Candidate.Origin.IMAGE_BOX, RegionKind.IMAGE, r.expand(-1.5f),
                            labelText(labelRun, r), labelRun, 0.8);
                }
            }
        }
    }

    private static String photoLabel(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("logo")) {
            return "Logo";
        }
        if (t.contains("stamp")) {
            return "Stamp";
        }
        return "Photo";
    }

    private void emptyCell(PageModel page, PageModel.Cell cell, List<Candidate> out) {
        Rect r = cell.rect();
        TextRun labelRun = null;
        String label = null;
        double conf = 0.85;
        // form-style: the label is in the cell to the left
        PageModel.Cell left = neighbour(page, cell, -1);
        if (left != null) {
            List<TextRun> lt = page.runsIn(left.rect());
            if (!lt.isEmpty()) {
                labelRun = lt.get(0);
                label = Labels.clean(String.join(" ", lt.stream().map(TextRun::trimmed).toList()));
            }
        }
        if (label == null || label.isEmpty()) {
            // table-style: a header cell above in the same column
            PageModel.Cell header = headerAbove(page, cell);
            if (header != null) {
                List<TextRun> ht = page.runsIn(header.rect());
                String head = Labels.clean(String.join(" ", ht.stream().map(TextRun::trimmed).toList()));
                int row = cell.row() - header.row();
                labelRun = ht.isEmpty() ? null : ht.get(0);
                label = row > 0 ? head + " " + row : head;
                if (row > 0) {
                    String rowTitle = rowTitle(page, cell);
                    if (rowTitle != null) {
                        label = rowTitle + " " + head;
                    }
                }
            }
        }
        if (label == null || label.isEmpty()) {
            labelRun = labelLeft(page, r, 160);
            if (labelRun == null) {
                labelRun = labelAbove(page, r);
            }
            label = labelRun == null ? null : labelText(labelRun, r);
        }
        if (label == null || label.isEmpty()) {
            label = "Field";
            conf = 0.45;
        }
        RegionKind kind = Labels.kindFor(label, RegionKind.TEXT);
        if (kind == RegionKind.IMAGE && r.h() < 30) {
            kind = RegionKind.TEXT;
        }
        float size = labelRun == null ? Math.max(8f, page.bodySize) : Math.max(6f, Math.min(14f, labelRun.size));
        if (kind == RegionKind.TEXT && r.h() >= 2.4f * size * TextLayout.LINE_GAP) {
            kind = RegionKind.MULTILINE;
        }
        Candidate c = add(out, page, Candidate.Origin.CELL, kind, r.expand(-1.5f), label, labelRun, conf);
        if (kind == RegionKind.MULTILINE) {
            c.maxLines = Math.max(2, (int) ((r.h() - 3) / (size * TextLayout.LINE_GAP)));
        }
    }

    /** First-column text of the cell's row, when the table has a header row and the row has a title. */
    private static String rowTitle(PageModel page, PageModel.Cell cell) {
        if (cell.col() == 0) {
            return null;
        }
        for (PageModel.Cell c : page.cells) {
            if (c.table() == cell.table() && c.row() == cell.row() && c.col() == 0) {
                List<TextRun> t = page.runsIn(c.rect());
                if (!t.isEmpty()) {
                    String s = Labels.clean(String.join(" ", t.stream().map(TextRun::trimmed).toList()));
                    return Labels.looksLikeLabel(s) ? s : null;
                }
            }
        }
        return null;
    }

    private void labelledCell(PageModel page, PageModel.Cell cell, List<TextRun> inside, List<Candidate> out) {
        if (inside.size() != 1) {
            return;
        }
        long tableSize = page.cells.stream().filter(c -> c.table() == cell.table()).count();
        if (tableSize > 1 && (cell.row() == 0 || neighbour(page, cell, 1) != null)) {
            return;
        }
        TextRun label = inside.get(0);
        String text = label.trimmed();
        if (!text.endsWith(":") && !(Labels.looksLikeLabel(text) && label.bold)) {
            return;
        }
        Rect r = cell.rect();
        float right = r.x1() - 2 - (label.box.x1() + 3);
        float below = label.box.y0() - 1 - (r.y0() + 1);
        if (right >= 40 && right * label.box.h() >= below * (r.w() - 4)) {
            Rect box = new Rect(label.box.x1() + 3, Math.max(r.y0() + 1, label.baseline - 0.25f * label.size),
                    r.x1() - 2, Math.min(r.y1() - 1, label.baseline + 0.95f * label.size));
            add(out, page, Candidate.Origin.CELL, Labels.kindFor(text, RegionKind.TEXT) == RegionKind.IMAGE
                    ? RegionKind.TEXT : Labels.kindFor(text, RegionKind.TEXT), box, Labels.clean(text), label, 0.8);
        } else if (below >= 10) {
            Rect box = new Rect(r.x0() + 2, r.y0() + 1, r.x1() - 2, label.box.y0() - 1);
            RegionKind kind = Labels.kindFor(text, RegionKind.TEXT);
            if (kind == RegionKind.TEXT && box.h() >= 2.4f * label.size * TextLayout.LINE_GAP) {
                kind = RegionKind.MULTILINE;
            }
            Candidate c = add(out, page, Candidate.Origin.CELL, kind, box, Labels.clean(text), label, 0.8);
            if (kind == RegionKind.MULTILINE) {
                c.maxLines = Math.max(2, (int) (box.h() / (label.size * TextLayout.LINE_GAP)));
            }
        }
    }

    static PageModel.Cell neighbour(PageModel page, PageModel.Cell cell, int dir) {
        PageModel.Cell best = null;
        for (PageModel.Cell c : page.cells) {
            if (c == cell || c.table() != cell.table()) {
                continue;
            }
            boolean adjacent = dir < 0 ? Math.abs(c.rect().x1() - cell.rect().x0()) <= 1.5f
                    : Math.abs(c.rect().x0() - cell.rect().x1()) <= 1.5f;
            if (adjacent && c.rect().yOverlap(cell.rect()) > 0.5f * cell.rect().h()) {
                best = c;
            }
        }
        return best;
    }

    /** The nearest cell above in the same column that holds text (the column header). */
    static PageModel.Cell headerAbove(PageModel page, PageModel.Cell cell) {
        PageModel.Cell best = null;
        for (PageModel.Cell c : page.cells) {
            if (c.table() != cell.table() || c.rect().y0() < cell.rect().y1() - 1.5f) {
                continue;
            }
            if (c.rect().xOverlap(cell.rect()) < 0.6f * Math.min(c.rect().w(), cell.rect().w())) {
                continue;
            }
            if (page.runsIn(c.rect()).isEmpty()) {
                continue;
            }
            if (c.row() == 0 && (best == null || c.rect().y0() > best.rect().y0())) {
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ "Label:" with space after it

    private void labelSpace(PageModel page, List<Candidate> out) {
        float margin = 0;
        for (TextRun r : page.runs) {
            margin = Math.max(margin, r.box.x1());
        }
        margin = Math.max(margin, page.width() - 50);
        for (List<TextRun> line : page.lines) {
            for (int i = 0; i < line.size(); i++) {
                TextRun run = line.get(i);
                String t = run.trimmed();
                if (!t.endsWith(":") || !Labels.looksLikeLabel(t)) {
                    continue;
                }
                if (page.cellAt(run.box.cx(), run.box.cy()) != null || labelTaken(out, page.index(), run)) {
                    continue;
                }
                float obstacle = i + 1 < line.size() ? line.get(i + 1).box.x0() : margin;
                for (Shapes.Segment s : page.segments) {
                    if (!s.horizontal() && s.pos() > run.box.x1() + 1 && s.pos() < obstacle
                            && s.from() < run.baseline + run.size && s.to() > run.baseline) {
                        obstacle = s.pos();
                    }
                }
                float x0 = run.box.x1() + 0.3f * run.size;
                float x1 = obstacle - 3;
                if (x1 - x0 < 50) {
                    continue;
                }
                Rect box = new Rect(x0, run.baseline - 0.25f * run.size, x1, run.baseline + 0.95f * run.size);
                if (overlapsExisting(out, page.index(), box, 0.05)) {
                    continue;
                }
                RegionKind kind = Labels.kindFor(t, RegionKind.TEXT);
                if (kind == RegionKind.IMAGE) {
                    kind = RegionKind.TEXT;
                }
                add(out, page, Candidate.Origin.LABEL_SPACE, kind, box, Labels.clean(t), run,
                        introducesText(page, run) ? 0.5 : 0.7);
            }
        }
    }

    /** "The following applies:" with text lines right under it: more likely a lead-in than a field. */
    private static boolean introducesText(PageModel page, TextRun label) {
        for (TextRun r : page.runs) {
            float below = label.baseline - r.baseline;
            if (below > 0.5f * label.size && below < 1.8f * label.size && Math.abs(r.box.x0() - label.box.x0()) < 20
                    && !r.trimmed().endsWith(":") && Labels.words(r.text) >= 4) {
                return true;
            }
        }
        return false;
    }

    /** The run already labels a candidate, or a candidate with the same label sits close by. */
    static boolean labelTaken(List<Candidate> out, int page, TextRun run) {
        String label = Labels.clean(run.text).toLowerCase(java.util.Locale.ROOT);
        for (Candidate c : out) {
            if (c.page != page) {
                continue;
            }
            if (c.labelRun == run) {
                return true;
            }
            boolean near = Math.abs(c.box.y1() - run.baseline) < 60 && c.box.x0() - run.box.x1() < 250;
            if (near && c.label != null && c.label.toLowerCase(java.util.Locale.ROOT).equals(label)) {
                return true;
            }
        }
        return false;
    }

    static boolean overlapsExisting(List<Candidate> out, int page, Rect box, double minFraction) {
        for (Candidate c : out) {
            if (c.page == page && c.box.overlapArea(box) > minFraction * Math.min(box.area(), c.box.area())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ label search

    /** Nearest run to the left on the same line band (at most {@code maxGap} away). */
    static TextRun labelLeft(PageModel page, Rect box, float maxGap) {
        TextRun best = null;
        for (TextRun r : page.runs) {
            float cy = r.box.cy();
            boolean band = cy >= box.y0() - 3 && cy <= box.y1() + 3
                    || r.baseline >= box.y0() - 3 && r.baseline <= box.y1()
                    || box.h() > 2 * r.size && r.baseline >= box.y1() - 1.6f * r.size && r.baseline <= box.y1() + 3;
            if (!band || r.box.x1() > box.x0() + 2 || box.x0() - r.box.x1() > maxGap) {
                continue;
            }
            if (!Labels.looksLikeLabel(r.text) && !Labels.looksLikeLabel(lastPhrase(r.text))) {
                continue;
            }
            if (best == null || r.box.x1() > best.box.x1()) {
                best = r;
            }
        }
        return best;
    }

    static TextRun labelRight(PageModel page, Rect box) {
        TextRun best = null;
        for (TextRun r : page.runs) {
            float cy = r.box.cy();
            if (cy < box.y0() - 4 || cy > box.y1() + 4 || r.box.x0() < box.x1() - 1 || r.box.x0() - box.x1() > 40) {
                continue;
            }
            if (best == null || r.box.x0() < best.box.x0()) {
                best = r;
            }
        }
        return best;
    }

    static TextRun labelAbove(PageModel page, Rect box) {
        TextRun best = null;
        float bestDist = Float.MAX_VALUE;
        for (TextRun r : page.runs) {
            float dist = r.box.y0() - box.y1();
            if (dist < -2 || dist > 1.6f * r.size + 6 || r.box.x1() < box.x0() - 2 || r.box.x0() > box.x1()) {
                continue;
            }
            if (!Labels.looksLikeLabel(r.text)) {
                continue;
            }
            if (dist < bestDist) {
                best = r;
                bestDist = dist;
            }
        }
        return best;
    }

    static TextRun labelBelow(PageModel page, Rect box) {
        TextRun best = null;
        float bestDist = Float.MAX_VALUE;
        for (TextRun r : page.runs) {
            float dist = box.y0() - r.box.y1();
            if (dist < -3 || dist > 14 || r.box.xOverlap(box) <= 0) {
                continue;
            }
            if (!Labels.looksLikeLabel(r.text)) {
                continue;
            }
            if (dist < bestDist) {
                best = r;
                bestDist = dist;
            }
        }
        return best;
    }

    /** The label text of a run relative to a box: the last label phrase before the box when on the same line. */
    static String labelText(TextRun run, Rect box) {
        String t = run.text;
        Matcher m = Labels.LEADER.matcher(t);
        if (m.find()) {
            t = t.substring(0, m.start());
        }
        String cleaned = Labels.clean(t);
        if (!Labels.looksLikeLabel(cleaned)) {
            String last = lastPhrase(t);
            if (Labels.looksLikeLabel(last)) {
                cleaned = Labels.clean(last);
            }
        }
        return cleaned.isEmpty() ? "Field" : cleaned;
    }

    /** The text after the last colon-terminated value ("A: x  B:" gives "B"). */
    static String lastPhrase(String text) {
        String t = text.strip();
        if (t.endsWith(":")) {
            t = t.substring(0, t.length() - 1);
        }
        int cut = Math.max(t.lastIndexOf(':'), Math.max(t.lastIndexOf("  "), t.lastIndexOf('\t')));
        return cut >= 0 ? t.substring(cut + 1).strip() : t;
    }
}
