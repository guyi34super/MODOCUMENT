package com.modocument.doccreation.pdftemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Dedupes candidates, assigns groups, keys and reading order, and converts them to the contract records. */
final class RegionAssembler {

    private RegionAssembler() {
    }

    /** Blank candidates minus duplicates and minus those a data candidate already covers. */
    static List<Candidate> merge(List<Candidate> blanks, List<Candidate> data) {
        List<Candidate> out = new ArrayList<>(data);
        Set<Candidate> consumed = new HashSet<>();
        for (Candidate d : data) {
            if (d.mergedBlank != null) {
                consumed.add(d.mergedBlank);
            }
            for (Candidate b : blanks) {
                if (d.labelRun != null && b.labelRun == d.labelRun && b.origin != Candidate.Origin.ACRO
                        && b.kind != RegionKind.CHECKBOX) {
                    consumed.add(b);
                }
            }
        }
        List<Candidate> sorted = new ArrayList<>(blanks);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> -c.confidence));
        List<Candidate> kept = new ArrayList<>();
        for (Candidate b : sorted) {
            if (consumed.contains(b)) {
                continue;
            }
            boolean dup = false;
            for (Candidate o : out) {
                if (o.page == b.page && o.box.overlapArea(b.box) > 0.5 * Math.min(o.box.area(), b.box.area())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                for (Candidate o : kept) {
                    if (o.page == b.page && o.box.overlapArea(b.box) > 0.6 * Math.min(o.box.area(), b.box.area())) {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                kept.add(b);
            }
        }
        out.addAll(kept);
        return out;
    }

    /** Groups from the nearest heading above, reading order, unique keys. */
    static void finish(List<Candidate> cands, List<PageModel> pages) {
        Set<TextRun> labelRuns = new HashSet<>();
        Set<Glyph> dataGlyphs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Candidate c : cands) {
            if (c.labelRun != null) {
                labelRuns.add(c.labelRun);
            }
            dataGlyphs.addAll(c.valueGlyphs);
        }
        List<List<TextRun>> headings = new ArrayList<>();
        for (PageModel p : pages) {
            List<TextRun> hs = new ArrayList<>();
            for (TextRun r : p.runs) {
                if (!labelRuns.contains(r) && Labels.isHeading(r, p) && !isDataRun(r, dataGlyphs)) {
                    hs.add(r);
                }
            }
            headings.add(hs);
        }
        for (Candidate c : cands) {
            if (c.group == null) {
                c.group = groupFor(c, headings);
            }
        }
        cands.sort(Comparator.comparingInt((Candidate c) -> c.page)
                .thenComparingDouble(c -> -Math.round(c.box.y1() / 6f))
                .thenComparingDouble(c -> c.box.x0()));
        Set<String> used = new HashSet<>();
        int order = 0;
        for (Candidate c : cands) {
            c.sortOrder = order++;
            String label = c.label == null || c.label.isBlank() ? "Field" : c.label;
            c.label = label.length() > 200 ? label.substring(0, 200) : label;
            c.key = Labels.uniqueKey(Labels.slug(c.label), used);
            if (c.group != null && c.group.length() > 120) {
                c.group = c.group.substring(0, 120);
            }
        }
    }

    private static boolean isDataRun(TextRun r, Set<Glyph> dataGlyphs) {
        for (Glyph g : r.glyphs) {
            if (dataGlyphs.contains(g)) {
                return true;
            }
        }
        return false;
    }

    private static String groupFor(Candidate c, List<List<TextRun>> headings) {
        for (int p = c.page; p >= 0; p--) {
            TextRun best = null;
            float bestDist = Float.MAX_VALUE;
            for (TextRun h : headings.get(p)) {
                float dist = p == c.page ? h.box.y0() - c.box.y1() + 2 : 10_000 - h.box.y0();
                if (p == c.page && dist < 0) {
                    continue;
                }
                boolean column = h.box.xOverlap(c.box) > 0 || h.box.x0() <= c.box.x0() + 20;
                if (!column && p == c.page) {
                    dist += 400;
                }
                if (dist < bestDist) {
                    best = h;
                    bestDist = dist;
                }
            }
            if (best != null) {
                return Labels.clean(best.trimmed());
            }
        }
        return null;
    }

    static TemplateRegion toRegion(Candidate c, PageFrame frame) {
        Rect box = frame.toBoxSpace(c.box);
        int maxLines = switch (c.kind) {
            case CHECKBOX, IMAGE, SIGNATURE -> 0;
            case MULTILINE -> Math.max(1, c.maxLines);
            default -> 1;
        };
        return new TemplateRegion(c.key, c.label, c.group, c.kind, box.toBox(c.page),
                c.fontName == null ? "LiberationSans" : c.fontName, Math.max(0f, c.fontSize), hex(c.rgb),
                c.align == null ? Align.LEFT : c.align, maxLines, c.acroName, c.required, c.sortOrder,
                c.source == null ? RegionSource.DETECTED : c.source, round(c.confidence), c.sample);
    }

    static TemplateEdit toRemoval(Candidate c, PageFrame frame, String seed) {
        Rect box = frame.toBoxSpace(c.removeBox);
        String id = UUID.nameUUIDFromBytes((seed + "|" + c.page + "|" + box).getBytes(StandardCharsets.UTF_8))
                .toString();
        return new TemplateEdit(id, EditKind.REMOVE, box.toBox(c.page), null, null, null, null, null,
                EditSource.DATA_REMOVAL, round(c.confidence), c.sample);
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    static int parseHex(String hex, int def) {
        if (hex == null || !hex.matches("#?[0-9a-fA-F]{6}")) {
            return def;
        }
        return Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16);
    }

    private static double round(double v) {
        return Math.round(Math.max(0, Math.min(1, v)) * 100) / 100.0;
    }

}
