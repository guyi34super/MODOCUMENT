package com.modocument.doccreation.pdftemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Finds rectangular cells formed by ruling lines (tables, boxes, checkbox squares), handling merged cells,
 * plus the horizontal lines that are not part of any cell (fill-in lines). Display space.
 */
final class CellDetector {

    private static final float TOL = 1.2f;

    record Result(List<PageModel.Cell> cells, List<Shapes.Segment> freeLines) {
    }

    private CellDetector() {
    }

    static Result detect(List<Shapes.Segment> raw, List<Shapes.FilledRect> fills, float pageW, float pageH) {
        List<Shapes.Segment> h = merge(raw, true);
        List<Shapes.Segment> v = merge(raw, false);
        List<Rect> rects = new ArrayList<>();
        float[] xs = clusters(v);
        float[] ys = clusters(h);
        if (xs.length >= 2 && ys.length >= 2 && (long) xs.length * ys.length <= 60_000) {
            rects.addAll(grid(xs, ys, h, v));
        }
        // filled boxes (input fields drawn as shaded rectangles) count as cells
        for (Shapes.FilledRect f : fills) {
            Rect r = f.rect();
            if (r.w() < 8 || r.h() < 5 || r.area() > 0.4f * pageW * pageH) {
                continue;
            }
            boolean dup = false;
            for (Rect c : rects) {
                if (c.iou(r) > 0.8) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                rects.add(r);
            }
        }
        rects.removeIf(r -> r.w() < 4 || r.h() < 4);
        List<PageModel.Cell> cells = assignTables(rects);
        List<Shapes.Segment> free = new ArrayList<>();
        for (Shapes.Segment s : h) {
            if (s.length() < 20) {
                continue;
            }
            boolean border = false;
            for (PageModel.Cell c : cells) {
                Rect r = c.rect();
                boolean onEdge = Math.abs(r.y0() - s.pos()) <= TOL || Math.abs(r.y1() - s.pos()) <= TOL;
                float overlap = Math.min(r.x1(), s.to()) - Math.max(r.x0(), s.from());
                if (onEdge && overlap > 0.5f * Math.min(s.length(), r.w())) {
                    border = true;
                    break;
                }
            }
            if (!border) {
                free.add(s);
            }
        }
        return new Result(cells, free);
    }

    /** Collinear segments joined (same position within tolerance, touching or overlapping). */
    static List<Shapes.Segment> merge(List<Shapes.Segment> raw, boolean horizontal) {
        List<Shapes.Segment> list = new ArrayList<>();
        for (Shapes.Segment s : raw) {
            if (s.horizontal() == horizontal) {
                list.add(s);
            }
        }
        list.sort(Comparator.comparingDouble(Shapes.Segment::pos).thenComparingDouble(Shapes.Segment::from));
        List<Shapes.Segment> out = new ArrayList<>();
        List<Shapes.Segment> group = new ArrayList<>();
        for (Shapes.Segment s : list) {
            if (!group.isEmpty() && Math.abs(s.pos() - group.get(0).pos()) > TOL) {
                out.addAll(joinGroup(group, horizontal));
                group.clear();
            }
            group.add(s);
        }
        out.addAll(joinGroup(group, horizontal));
        return out;
    }

    private static List<Shapes.Segment> joinGroup(List<Shapes.Segment> group, boolean horizontal) {
        List<Shapes.Segment> out = new ArrayList<>();
        if (group.isEmpty()) {
            return out;
        }
        group.sort(Comparator.comparingDouble(Shapes.Segment::from));
        float pos = 0;
        for (Shapes.Segment s : group) {
            pos += s.pos();
        }
        pos /= group.size();
        float from = group.get(0).from();
        float to = group.get(0).to();
        for (int i = 1; i < group.size(); i++) {
            Shapes.Segment s = group.get(i);
            if (s.from() <= to + 1.5f) {
                to = Math.max(to, s.to());
            } else {
                out.add(new Shapes.Segment(horizontal, pos, from, to));
                from = s.from();
                to = s.to();
            }
        }
        out.add(new Shapes.Segment(horizontal, pos, from, to));
        return out;
    }

    private static float[] clusters(List<Shapes.Segment> segs) {
        TreeSet<Float> sorted = new TreeSet<>();
        for (Shapes.Segment s : segs) {
            sorted.add(s.pos());
        }
        List<Float> out = new ArrayList<>();
        for (float p : sorted) {
            if (out.isEmpty() || p - out.get(out.size() - 1) > TOL) {
                out.add(p);
            }
        }
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    private static boolean covered(java.util.NavigableMap<Float, List<Shapes.Segment>> index, float pos, float from,
                                   float to) {
        for (List<Shapes.Segment> list : index.subMap(pos - TOL, true, pos + TOL, true).values()) {
            for (Shapes.Segment s : list) {
                if (s.from() <= from + TOL && s.to() >= to - TOL) {
                    return true;
                }
            }
        }
        return false;
    }

    private static java.util.NavigableMap<Float, List<Shapes.Segment>> index(List<Shapes.Segment> segs) {
        java.util.TreeMap<Float, List<Shapes.Segment>> out = new java.util.TreeMap<>();
        for (Shapes.Segment s : segs) {
            out.computeIfAbsent(s.pos(), k -> new ArrayList<>()).add(s);
        }
        return out;
    }

    private static List<Rect> grid(float[] xs, float[] ys, List<Shapes.Segment> h, List<Shapes.Segment> v) {
        int nx = xs.length - 1;
        int ny = ys.length - 1;
        java.util.NavigableMap<Float, List<Shapes.Segment>> hIndex = index(h);
        java.util.NavigableMap<Float, List<Shapes.Segment>> vIndex = index(v);
        boolean[][] right = new boolean[nx + 1][ny];
        boolean[][] top = new boolean[nx][ny + 1];
        for (int i = 0; i <= nx; i++) {
            for (int j = 0; j < ny; j++) {
                right[i][j] = covered(vIndex, xs[i], ys[j], ys[j + 1]);
            }
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j <= ny; j++) {
                top[i][j] = covered(hIndex, ys[j], xs[i], xs[i + 1]);
            }
        }
        int[] parent = new int[nx * ny];
        for (int k = 0; k < parent.length; k++) {
            parent[k] = k;
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                if (i + 1 < nx && !right[i + 1][j]) {
                    union(parent, i * ny + j, (i + 1) * ny + j);
                }
                if (j + 1 < ny && !top[i][j + 1]) {
                    union(parent, i * ny + j, i * ny + j + 1);
                }
            }
        }
        Map<Integer, int[]> groups = new HashMap<>();
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                int root = find(parent, i * ny + j);
                int[] g = groups.computeIfAbsent(root, r -> new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1, 0});
                g[0] = Math.min(g[0], i);
                g[1] = Math.min(g[1], j);
                g[2] = Math.max(g[2], i);
                g[3] = Math.max(g[3], j);
                g[4]++;
            }
        }
        List<Rect> out = new ArrayList<>();
        for (int[] g : groups.values()) {
            int i0 = g[0];
            int j0 = g[1];
            int i1 = g[2];
            int j1 = g[3];
            if ((i1 - i0 + 1) * (j1 - j0 + 1) != g[4]) {
                continue;
            }
            boolean closed = true;
            for (int j = j0; j <= j1 && closed; j++) {
                closed = right[i0][j] && right[i1 + 1][j];
            }
            for (int i = i0; i <= i1 && closed; i++) {
                closed = top[i][j0] && top[i][j1 + 1];
            }
            if (closed) {
                out.add(new Rect(xs[i0], ys[j0], xs[i1 + 1], ys[j1 + 1]));
            }
        }
        return out;
    }

    private static int find(int[] p, int k) {
        while (p[k] != k) {
            p[k] = p[p[k]];
            k = p[k];
        }
        return k;
    }

    private static void union(int[] p, int a, int b) {
        int ra = find(p, a);
        int rb = find(p, b);
        if (ra != rb) {
            p[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    /** Groups touching cells into tables and numbers rows (top first) and columns (left first). */
    private static List<PageModel.Cell> assignTables(List<Rect> rects) {
        int n = rects.size();
        int[] parent = new int[n];
        for (int k = 0; k < n; k++) {
            parent[k] = k;
        }
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                Rect r = rects.get(a);
                Rect s = rects.get(b);
                boolean touchX = (Math.abs(r.x1() - s.x0()) <= TOL || Math.abs(s.x1() - r.x0()) <= TOL)
                        && r.yOverlap(s) > 1;
                boolean touchY = (Math.abs(r.y1() - s.y0()) <= TOL || Math.abs(s.y1() - r.y0()) <= TOL)
                        && r.xOverlap(s) > 1;
                if (touchX || touchY) {
                    union(parent, a, b);
                }
            }
        }
        Map<Integer, List<Integer>> tables = new HashMap<>();
        for (int k = 0; k < n; k++) {
            tables.computeIfAbsent(find(parent, k), x -> new ArrayList<>()).add(k);
        }
        List<PageModel.Cell> cells = new ArrayList<>();
        int tableId = 0;
        List<List<Integer>> ordered = new ArrayList<>(tables.values());
        ordered.sort(Comparator.comparingDouble((List<Integer> t) -> -rects.get(t.get(0)).y1())
                .thenComparingDouble(t -> rects.get(t.get(0)).x0()));
        for (List<Integer> members : ordered) {
            TreeSet<Float> tops = new TreeSet<>(Comparator.reverseOrder());
            TreeSet<Float> lefts = new TreeSet<>();
            for (int k : members) {
                tops.add(round(rects.get(k).y1()));
                lefts.add(round(rects.get(k).x0()));
            }
            List<Float> topList = new ArrayList<>(tops);
            List<Float> leftList = new ArrayList<>(lefts);
            for (int k : members) {
                Rect r = rects.get(k);
                cells.add(new PageModel.Cell(r, tableId, topList.indexOf(round(r.y1())), leftList.indexOf(round(r.x0()))));
            }
            tableId++;
        }
        cells.sort(Comparator.comparingInt(PageModel.Cell::table).thenComparingInt(PageModel.Cell::row)
                .thenComparingInt(PageModel.Cell::col));
        return cells;
    }

    private static float round(float v) {
        return Math.round(v * 2f) / 2f;
    }
}
