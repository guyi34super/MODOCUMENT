package com.modocument.doccreation.pdftemplate;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional AI pass over the code result (contract §3, purposes DOC_PDF_REGIONS and DOC_PDF_DATA). The model
 * sees a masked listing of the text runs (ids and coordinates) and the code's candidates; it may relabel,
 * regroup and re-kind candidates, and point at further runs that are data or at proposed data that is really
 * template text. Every id is checked against the real page model and the whole reply is dropped when
 * anything does not check out; geometry always comes from the real glyphs. Any failure leaves the code
 * result unchanged.
 */
final class AiRegionAdvisor {

    private static final Logger log = LoggerFactory.getLogger(AiRegionAdvisor.class);
    static final int MAX_LISTING_CHARS = 24_000;
    private static final Pattern MASK_TOKEN = Pattern.compile("\\[[A-Z]+(_\\d+)?]");

    private static final String REGIONS_PROMPT = """
            You are labelling the fill-in fields of a PDF form template.
            RUNS lists the text on the pages: id, page, box (x,y,w,h in points, origin bottom-left), font size, \
            B for bold, then the text in quotes. CANDIDATES lists the fill-in fields that code found: id, page, \
            kind, box, current label.
            For each candidate whose label, group or kind you can improve, return: candidateId, label (short, \
            as printed on the form), group (the section heading it belongs to, or null), kind (one of TEXT, \
            MULTILINE, DATE, NUMBER, CHECKBOX, IMAGE, SIGNATURE) and labelRunId (the run id of its printed \
            label, or null).
            Rules: use only candidate and run ids from the lists; never invent fields; leave out candidates \
            you cannot improve. Reply with JSON only: {"regions":[...]}.

            """;

    private static final String DATA_PROMPT = """
            This PDF is a FILLED-IN document that is being turned into a reusable template, so its sample \
            data must be found. DATA is what someone typed or wrote into the form (names, dates, amounts, \
            IDs, addresses, answers, ticks). TEMPLATE text is everything printed on the blank form (titles, \
            headings, field labels, instructions, boilerplate, page numbers, letterhead).
            RUNS lists the text: id, page, box (x,y,w,h in points, origin bottom-left), font size, B for bold, \
            then the text in quotes. PROPOSED lists the run ids code already classified as data.
            Return dataRunIds (further runs that are data, not already proposed), templateRunIds (proposed \
            runs that are really template text) and labels (for each data run, the run id of its field label: \
            {"runId":..,"labelRunId":..}).
            Rules: use only run ids from the list; when unsure, leave a run out. Reply with JSON only: \
            {"dataRunIds":[...],"templateRunIds":[...],"labels":[...]}.

            """;

    /** Reply shapes; validated before anything is used. */
    record RegionHint(String candidateId, String label, String group, String kind, String labelRunId) {
    }

    record RegionsReply(List<RegionHint> regions) {
    }

    record LabelHint(String runId, String labelRunId) {
    }

    record DataReply(List<String> dataRunIds, List<String> templateRunIds, List<LabelHint> labels) {
    }

    private final AiFilesService ai;
    private final LlmRateLimiter llmQuota;
    private final CurrentUserProvider currentUser;
    private final PiiTokenizer tokenizer;

    AiRegionAdvisor(AiFilesService ai, LlmRateLimiter llmQuota, CurrentUserProvider currentUser,
                    PiiTokenizer tokenizer) {
        this.ai = ai;
        this.llmQuota = llmQuota;
        this.currentUser = currentUser;
        this.tokenizer = tokenizer;
    }

    boolean available() {
        try {
            return ai != null && ai.isConfigured();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ listing

    /** The masked run listing; only runs listed here may be referenced. */
    static final class Listing {
        final Map<String, TextRun> runs = new HashMap<>();
        final Map<TextRun, PageModel> pageOf = new IdentityHashMap<>();
        final StringBuilder text = new StringBuilder();
        boolean truncated;
    }

    static Listing listing(List<PageModel> pages, int budget) {
        Listing l = new Listing();
        l.text.append("RUNS\n");
        outer:
        for (PageModel p : pages) {
            for (List<TextRun> line : p.lines) {
                for (TextRun r : line) {
                    String t = r.trimmed().replace('"', '\'');
                    if (t.length() > 120) {
                        t = t.substring(0, 120) + "…";
                    }
                    String row = r.id + " p" + p.index() + " " + coords(r.box) + " s" + Math.round(r.size)
                            + (r.bold ? " B" : "") + " \"" + t + "\"\n";
                    if (l.text.length() + row.length() > budget) {
                        l.truncated = true;
                        break outer;
                    }
                    l.text.append(row);
                    l.runs.put(r.id, r);
                    l.pageOf.put(r, p);
                }
            }
        }
        return l;
    }

    static String coords(Rect r) {
        return Math.round(r.x0()) + "," + Math.round(r.y0()) + "," + Math.round(r.w()) + "," + Math.round(r.h());
    }

    // ------------------------------------------------------------------ regions

    /** Relabels/regroups/re-kinds candidates. True when a valid reply was applied. */
    boolean improveRegions(List<PageModel> pages, List<Candidate> cands, List<String> warnings) {
        if (cands.isEmpty()) {
            return false;
        }
        try {
            Listing l = listing(pages, MAX_LISTING_CHARS - 4000);
            StringBuilder sb = new StringBuilder(l.text).append("CANDIDATES\n");
            Map<String, Candidate> byId = new HashMap<>();
            for (Candidate c : cands) {
                String row = c.id + " p" + c.page + " " + c.kind + " " + coords(c.box) + " \""
                        + (c.label == null ? "" : c.label.replace('"', '\'')) + "\"\n";
                if (sb.length() + row.length() > MAX_LISTING_CHARS) {
                    break;
                }
                sb.append(row);
                byId.put(c.id, c);
            }
            RegionsReply reply = send(AiPurpose.DOC_PDF_REGIONS, REGIONS_PROMPT + tokenizer.maskText(sb.toString()),
                    new ParameterizedTypeReference<RegionsReply>() {
                    });
            String invalid = validateRegions(reply, byId, l);
            if (invalid != null) {
                log.warn("AI region labels rejected ({}); keeping the code labels", invalid);
                warnings.add("The AI suggestion for field labels was not usable; the code labels were kept.");
                return false;
            }
            for (RegionHint h : reply.regions()) {
                Candidate c = byId.get(h.candidateId());
                if (h.label() != null && !h.label().isBlank()) {
                    c.label = Labels.clean(h.label());
                    if (h.labelRunId() != null) {
                        c.labelRun = l.runs.get(h.labelRunId());
                    }
                }
                if (h.group() != null && !h.group().isBlank()) {
                    c.group = Labels.clean(h.group());
                }
                if (h.kind() != null && !h.kind().isBlank()) {
                    RegionKind k = RegionKind.valueOf(h.kind().strip().toUpperCase(Locale.ROOT));
                    if (kindFits(c, k)) {
                        c.kind = k;
                        c.maxLines = switch (k) {
                            case CHECKBOX, IMAGE, SIGNATURE -> 0;
                            case MULTILINE -> Math.max(2, c.maxLines);
                            default -> 1;
                        };
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("AI region labels unavailable, keeping the code result: {}", e.getMessage());
            return false;
        }
    }

    private static boolean kindFits(Candidate c, RegionKind k) {
        if (c.origin == Candidate.Origin.ACRO) {
            boolean box = c.kind == RegionKind.CHECKBOX;
            return box == (k == RegionKind.CHECKBOX) && (k != RegionKind.IMAGE);
        }
        return switch (k) {
            case CHECKBOX -> c.box.w() <= 24 && c.box.h() <= 24;
            case IMAGE, SIGNATURE -> c.box.h() >= 10 && c.box.w() >= 20 && c.valueGlyphs.isEmpty();
            default -> c.kind != RegionKind.CHECKBOX && c.kind != RegionKind.IMAGE || !c.isData();
        };
    }

    static String validateRegions(RegionsReply reply, Map<String, Candidate> byId, Listing l) {
        if (reply == null || reply.regions() == null) {
            return "no regions";
        }
        Set<String> seen = new HashSet<>();
        for (RegionHint h : reply.regions()) {
            if (h == null || h.candidateId() == null || !byId.containsKey(h.candidateId())) {
                return "unknown candidate " + (h == null ? null : h.candidateId());
            }
            if (!seen.add(h.candidateId())) {
                return "candidate " + h.candidateId() + " twice";
            }
            if (h.labelRunId() != null && !l.runs.containsKey(h.labelRunId())) {
                return "unknown run " + h.labelRunId();
            }
            String bad = badText(h.label(), 200);
            if (bad == null) {
                bad = badText(h.group(), 120);
            }
            if (bad != null) {
                return bad;
            }
            if (h.kind() != null && !h.kind().isBlank()) {
                try {
                    RegionKind.valueOf(h.kind().strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "unknown kind " + h.kind();
                }
            }
        }
        return null;
    }

    private static String badText(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() > max) {
            return "text too long";
        }
        if (MASK_TOKEN.matcher(s).find()) {
            return "masked placeholder in a label";
        }
        if (s.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            return "control characters in a label";
        }
        return null;
    }

    // ------------------------------------------------------------------ data

    /**
     * Adds data runs the code missed (AI-only items get confidence 0.6, shown for review) and demotes code
     * proposals the model calls template text (never below the review line by deletion: they stay, at 0.4).
     */
    boolean improveData(List<PageModel> pages, List<Candidate> data, List<Candidate> blanks,
                        SampleDataDetector detector, List<String> warnings) {
        try {
            Listing l = listing(pages, MAX_LISTING_CHARS - 3000);
            Map<Glyph, TextRun> runOf = new IdentityHashMap<>();
            for (TextRun r : l.runs.values()) {
                for (Glyph g : r.glyphs) {
                    runOf.put(g, r);
                }
            }
            Map<TextRun, Candidate> proposedRuns = new IdentityHashMap<>();
            for (Candidate c : data) {
                for (Glyph g : c.valueGlyphs) {
                    TextRun r = runOf.get(g);
                    if (r != null) {
                        proposedRuns.put(r, c);
                    }
                }
            }
            StringBuilder sb = new StringBuilder(l.text).append("PROPOSED\n");
            for (TextRun r : proposedRuns.keySet()) {
                sb.append(r.id).append(' ');
            }
            sb.append('\n');
            DataReply reply = send(AiPurpose.DOC_PDF_DATA, DATA_PROMPT + tokenizer.maskText(sb.toString()),
                    new ParameterizedTypeReference<DataReply>() {
                    });
            Set<TextRun> protectedRuns = protectedRuns(pages, data, blanks);
            String invalid = validateData(reply, l, proposedRuns, protectedRuns);
            if (invalid != null) {
                log.warn("AI data classification rejected ({}); keeping the code result", invalid);
                warnings.add("The AI suggestion for the sample data was not usable; the code result was kept.");
                return false;
            }
            Map<String, String> labelOf = new HashMap<>();
            if (reply.labels() != null) {
                for (LabelHint h : reply.labels()) {
                    labelOf.put(h.runId(), h.labelRunId());
                }
            }
            for (String id : nullSafe(reply.templateRunIds())) {
                Candidate c = proposedRuns.get(l.runs.get(id));
                if (c != null) {
                    c.confidence = Math.min(c.confidence, 0.4);
                }
            }
            for (String id : nullSafe(reply.dataRunIds())) {
                TextRun run = l.runs.get(id);
                Candidate existing = proposedRuns.get(run);
                if (existing != null) {
                    // AI agrees with a code proposal: lift a review-only item over the line
                    existing.confidence = Math.max(existing.confidence, Math.min(0.75, existing.confidence + 0.2));
                    continue;
                }
                String labelId = labelOf.get(id);
                TextRun labelRun = labelId == null ? null : l.runs.get(labelId);
                Candidate c = detector.fromRun(l.pageOf.get(run), run, labelRun, 0.6);
                if (c != null) {
                    c.source = RegionSource.AI;
                    data.add(c);
                }
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("AI data classification unavailable, keeping the code result: {}", e.getMessage());
            return false;
        }
    }

    /** Headings and labels: the AI may never turn them into data. */
    static Set<TextRun> protectedRuns(List<PageModel> pages, List<Candidate> data, List<Candidate> blanks) {
        Set<TextRun> out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (PageModel p : pages) {
            for (TextRun r : p.runs) {
                String t = r.trimmed();
                if (Labels.isHeading(r, p) || t.endsWith(":") || Labels.LEADER.matcher(t).matches()) {
                    out.add(r);
                }
            }
        }
        for (Candidate c : data) {
            if (c.labelRun != null) {
                out.add(c.labelRun);
            }
        }
        for (Candidate c : blanks) {
            if (c.labelRun != null) {
                out.add(c.labelRun);
            }
        }
        return out;
    }

    static String validateData(DataReply reply, Listing l, Map<TextRun, Candidate> proposed, Set<TextRun> protectedRuns) {
        if (reply == null) {
            return "empty reply";
        }
        Set<String> seen = new HashSet<>();
        for (String id : nullSafe(reply.dataRunIds())) {
            TextRun r = id == null ? null : l.runs.get(id);
            if (r == null) {
                return "unknown run " + id;
            }
            if (!seen.add(id)) {
                return "run " + id + " twice";
            }
            if (protectedRuns.contains(r) && !proposed.containsKey(r)) {
                return "run " + id + " is a heading or label";
            }
        }
        for (String id : nullSafe(reply.templateRunIds())) {
            if (id == null || !l.runs.containsKey(id)) {
                return "unknown run " + id;
            }
            if (seen.contains(id)) {
                return "run " + id + " is both data and template";
            }
        }
        if (reply.labels() != null) {
            for (LabelHint h : reply.labels()) {
                if (h == null || h.runId() == null || !l.runs.containsKey(h.runId())
                        || h.labelRunId() != null && !l.runs.containsKey(h.labelRunId())) {
                    return "label hint with an unknown run";
                }
            }
        }
        return null;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private <T> T send(AiPurpose purpose, String prompt, ParameterizedTypeReference<T> type) {
        int user = currentUser.requireUserId();
        llmQuota.check(user);
        return ai.sendForObject(PromptEnvelope.builder(purpose).user(user).part(prompt).build(), type);
    }
}
