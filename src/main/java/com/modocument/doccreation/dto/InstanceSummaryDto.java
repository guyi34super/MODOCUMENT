package com.modocument.doccreation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.entity.DocInstance;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * One entry of {@code GET /api/doccreation/instances}; {@code id} repeats {@code instanceId}.
 *
 * <p>ws34 (data-templates-contract §5): {@code validationState} is
 * {@link #STATE_VALIDATED} for a VALIDATED, GENERATED, SUBMITTED or RELEASED
 * document that passed, {@link #STATE_RULES_CHANGED} for a VALIDATED or
 * GENERATED one validated with an older rules revision than its
 * template's, {@link #STATE_ERRORS} for a DRAFT whose last check failed and
 * {@link #STATE_NOT_CHECKED} for a DRAFT never checked, or checked (passed)
 * and changed since; {@code errorCount} is the number of errors of that
 * failed check (0 otherwise).
 */
public record InstanceSummaryDto(int id,
                                 int instanceId,
                                 String title,
                                 int blueprintId,
                                 String blueprintName,
                                 String status,
                                 LocalDateTime lastGeneratedDate,
                                 LocalDateTime createdDate,
                                 boolean aiComposed,
                                 LocalDateTime submittedAt,
                                 LocalDateTime releasedAt,
                                 String reviewComment,
                                 int pageCount,
                                 String validationState,
                                 int errorCount) {

    public static final String STATE_NOT_CHECKED = "NOT_CHECKED";
    public static final String STATE_ERRORS = "ERRORS";
    public static final String STATE_VALIDATED = "VALIDATED";
    public static final String STATE_RULES_CHANGED = "RULES_CHANGED";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Without the template's rules revision: a validated document never reads RULES_CHANGED. */
    public static InstanceSummaryDto from(DocInstance instance, String blueprintName, int pageCount) {
        return from(instance, blueprintName, pageCount, null);
    }

    /** {@code blueprintRulesRevision} is the template's RulesRevision (null when unknown). */
    public static InstanceSummaryDto from(DocInstance instance, String blueprintName, int pageCount,
                                          Integer blueprintRulesRevision) {
        String state = validationState(instance, blueprintRulesRevision);
        int errors = STATE_ERRORS.equals(state) ? storedErrorCount(instance) : 0;
        return new InstanceSummaryDto(instance.getDocInstanceId(), instance.getDocInstanceId(), instance.getTitle(),
                instance.getBlueprintId(), blueprintName, instance.getStatus(),
                instance.getLastGeneratedDate(), instance.getCreatedDate(),
                instance.isAiComposed(), instance.getSubmittedAt(),
                instance.getReleasedAt(), instance.getReviewComment(), pageCount, state, errors);
    }

    /** The {@code validationState} of a document (class comment). */
    public static String validationState(DocInstance instance, Integer blueprintRulesRevision) {
        String status = instance.getStatus();
        if (DocInstance.STATUS_SUBMITTED.equals(status) || DocInstance.STATUS_RELEASED.equals(status)) {
            return STATE_VALIDATED;
        }
        if (DocInstance.STATUS_VALIDATED.equals(status) || DocInstance.STATUS_GENERATED.equals(status)) {
            Integer validated = instance.getValidatedRulesRevision();
            return validated != null && blueprintRulesRevision != null
                    && !validated.equals(blueprintRulesRevision) ? STATE_RULES_CHANGED : STATE_VALIDATED;
        }
        return storedErrorCount(instance) > 0 ? STATE_ERRORS : STATE_NOT_CHECKED;
    }

    /** The number of errors of the stored validation result ({@code ValidationJson}); 0 when none or unreadable. */
    static int storedErrorCount(DocInstance instance) {
        String json = instance.getValidationJson();
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            JsonNode errors = JSON.readTree(json).get("errors");
            return errors != null && errors.isArray() ? errors.size() : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }
}
