package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Photo auto-fill, code only (no AI): places uploaded photos into the empty
 * photo boxes of a document.
 * <ol>
 *   <li><b>Name match.</b> The file name's tokens ({@code front_of_house-2.jpg}
 *       → front, of, house, 2) are matched against each empty IMAGE or
 *       SIGNATURE region's label, key and group tokens. A match needs at least
 *       one shared word (a shared number alone never counts); more shared
 *       tokens win, then reading order. Best pairs are taken first.</li>
 *   <li><b>Reading order.</b> The photos left fill the remaining empty IMAGE
 *       regions in reading order (sort order, then page, then top to
 *       bottom, left to right). SIGNATURE regions are only ever filled by
 *       name.</li>
 * </ol>
 * Photos that find no box stay in the tray.
 */
public final class PhotoAutoFill {

    private static final Set<String> STOP_WORDS = Set.of("img", "image", "jpg", "jpeg", "png", "gif", "bmp",
            "photo", "pic", "dsc", "scan", "copy", "of", "the", "and", "a", "an");

    private PhotoAutoFill() {
    }

    /** A photo to place: its id and file name. */
    public record Photo(int id, String fileName) {
    }

    /**
     * @param photos  the photos to place, in upload order
     * @param regions the document's regions
     * @param filled  the region keys that already hold a photo
     * @return photo id → region key, for the photos placed
     */
    public static Map<Integer, String> assign(List<Photo> photos, List<TemplateRegion> regions,
                                              Set<String> filled) {
        List<TemplateRegion> open = regions.stream()
                .filter(r -> r.kind() == RegionKind.IMAGE || r.kind() == RegionKind.SIGNATURE)
                .filter(r -> !filled.contains(r.key()))
                .sorted(readingOrder())
                .toList();
        Map<Integer, String> placed = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();

        // 1. Name match: every (photo, region) pair with a real shared word,
        //    best score first, ties by upload order then reading order.
        record Candidate(Photo photo, TemplateRegion region, int score, int photoIndex, int regionIndex) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int p = 0; p < photos.size(); p++) {
            Set<String> photoTokens = tokens(baseName(photos.get(p).fileName()));
            for (int r = 0; r < open.size(); r++) {
                TemplateRegion region = open.get(r);
                Set<String> regionTokens = new LinkedHashSet<>();
                regionTokens.addAll(tokens(region.label()));
                regionTokens.addAll(tokens(region.key()));
                regionTokens.addAll(tokens(region.group()));
                int score = 0;
                boolean word = false;
                for (String token : photoTokens) {
                    if (regionTokens.contains(token)) {
                        score++;
                        word |= !isNumber(token);
                    }
                }
                if (word) {
                    candidates.add(new Candidate(photos.get(p), region, score, p, r));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparingInt(Candidate::photoIndex)
                .thenComparingInt(Candidate::regionIndex));
        for (Candidate candidate : candidates) {
            if (placed.containsKey(candidate.photo().id()) || taken.contains(candidate.region().key())) {
                continue;
            }
            placed.put(candidate.photo().id(), candidate.region().key());
            taken.add(candidate.region().key());
        }

        // 2. Reading order: the rest into the empty IMAGE regions.
        List<TemplateRegion> remaining = open.stream()
                .filter(r -> r.kind() == RegionKind.IMAGE && !taken.contains(r.key()))
                .toList();
        int next = 0;
        for (Photo photo : photos) {
            if (placed.containsKey(photo.id())) {
                continue;
            }
            if (next >= remaining.size()) {
                break;
            }
            placed.put(photo.id(), remaining.get(next++).key());
        }
        return placed;
    }

    /** Sort order, then page, then top to bottom (higher y first in PDF space), then left to right. */
    static Comparator<TemplateRegion> readingOrder() {
        return Comparator.comparingInt(TemplateRegion::sortOrder)
                .thenComparingInt(r -> r.box().page())
                .thenComparing(r -> -(r.box().y() + r.box().h()))
                .thenComparing(r -> r.box().x());
    }

    /** Lower-case word and number tokens; camelCase and letter/digit boundaries split. */
    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String spaced = text.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Za-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([A-Za-z])", "$1 $2");
        for (String token : spaced.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty() || STOP_WORDS.contains(token)) {
                continue;
            }
            out.add(isNumber(token) ? String.valueOf(Long.parseLong(token.length() > 18
                    ? token.substring(token.length() - 18) : token)) : token);
        }
        return out;
    }

    private static boolean isNumber(String token) {
        return !token.isEmpty() && token.chars().allMatch(Character::isDigit);
    }

    private static String baseName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
