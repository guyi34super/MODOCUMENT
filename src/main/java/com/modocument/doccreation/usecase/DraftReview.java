package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.dto.EditDto;
import com.modocument.doccreation.dto.RegionDto;

import java.util.List;
import java.util.Map;

/**
 * The review data of a DRAFT template (contract §5): the sample values the
 * WITH_DATA flow removed (by region key), the removed texts and confidences
 * of the proposed removals (by edit id), the analysis warnings and who
 * detected the regions. It is customer data copied from the upload, so it
 * lives ONLY in a draft file next to the template's files and is deleted
 * when the template is confirmed; nothing of it reaches a database row.
 *
 * <p>BUG-PDF-01: {@code suggestions} are the proposed removals below
 * {@link RemovalPolicy#APPLY_THRESHOLD} (API shape, {@code applied:false},
 * with their removed text) and {@code suggestedRegions} the DATA_REMOVED
 * regions linked to them ({@code suggested:true}, with their sample
 * value): neither is applied to the template nor stored in a row unless the
 * client sends them back as applied/kept. {@code editRegions} links each
 * APPLIED proposed removal (by edit id) to its region key.
 */
record DraftReview(Map<String, String> samples,
                   Map<String, String> removed,
                   Map<String, Double> editConfidence,
                   List<String> warnings,
                   String detectedBy,
                   List<EditDto> suggestions,
                   List<RegionDto> suggestedRegions,
                   Map<String, String> editRegions) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final DraftReview EMPTY = new DraftReview(Map.of(), Map.of(), Map.of(), List.of(), null, List.of(),
            List.of(), Map.of());

    DraftReview {
        samples = samples == null ? Map.of() : samples;
        removed = removed == null ? Map.of() : removed;
        editConfidence = editConfidence == null ? Map.of() : editConfidence;
        warnings = warnings == null ? List.of() : warnings;
        suggestions = suggestions == null ? List.of() : suggestions;
        suggestedRegions = suggestedRegions == null ? List.of() : suggestedRegions;
        editRegions = editRegions == null ? Map.of() : editRegions;
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
