package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * The review data of a DRAFT template (contract §5): the sample values the
 * WITH_DATA flow removed (by region key), the removed texts and confidences
 * of the proposed removals (by edit id), the analysis warnings and who
 * detected the regions. It is customer data copied from the upload, so it
 * lives ONLY in a draft file next to the template's files and is deleted
 * when the template is confirmed; nothing of it reaches a database row.
 */
record DraftReview(Map<String, String> samples,
                   Map<String, String> removed,
                   Map<String, Double> editConfidence,
                   List<String> warnings,
                   String detectedBy) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final DraftReview EMPTY = new DraftReview(Map.of(), Map.of(), Map.of(), List.of(), null);

    DraftReview {
        samples = samples == null ? Map.of() : samples;
        removed = removed == null ? Map.of() : removed;
        editConfidence = editConfidence == null ? Map.of() : editConfidence;
        warnings = warnings == null ? List.of() : warnings;
    }

    byte[] toJson() {
        try {
            return JSON.writeValueAsBytes(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the template review data.", e);
        }
    }

    static DraftReview fromJson(byte[] json) {
        try {
            return JSON.readValue(json, DraftReview.class);
        } catch (java.io.IOException e) {
            return EMPTY;
        }
    }
}
