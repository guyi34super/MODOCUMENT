package com.modocument.doccreation.flow;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Assigns an uploaded image to a blueprint section by matching the section's
 * keywords against the file name: "roof" claims Roof_East_02.jpg, "dc2" claims
 * dc2-overview.png. The best-scoring section wins; no match means the image
 * stays in the Unsorted tray instead of being lost.
 */
@Component
public class KeywordSectionMatcher {

    public Optional<Integer> match(String originalFileName, List<DocBlueprintSection> sections) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return Optional.empty();
        }
        String baseName = stripExtension(originalFileName).toLowerCase(Locale.ROOT);

        Integer bestSectionId = null;
        int bestScore = 0;
        for (DocBlueprintSection section : sections) {
            int score = score(baseName, section.getKeywords());
            // Strict > keeps the earlier (lower OrderIndex) section on a tie.
            if (score > bestScore) {
                bestScore = score;
                bestSectionId = section.getDocBlueprintSectionId();
            }
        }
        return Optional.ofNullable(bestSectionId);
    }

    private static int score(String baseName, String commaSeparatedKeywords) {
        if (commaSeparatedKeywords == null || commaSeparatedKeywords.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String keyword : commaSeparatedKeywords.split(",")) {
            String needle = keyword.strip().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && baseName.contains(needle)) {
                score++;
            }
        }
        return score;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
