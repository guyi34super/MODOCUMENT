package com.modocument.doccreation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The result of checking a document's values (field-validation-contract
 * §4.2): the reply of {@code POST /blueprints/{id}/rules/test} and
 * {@code InstanceDto.validation}. Messages never contain values.
 *
 * @param state  VALIDATED or DRAFT
 * @param checked false while the document has never been validated
 */
public record ValidationResultDto(String state,
                                  LocalDateTime validatedAt,
                                  int rulesRevision,
                                  boolean rulesChangedSinceValidation,
                                  List<Issue> errors,
                                  List<Issue> warnings,
                                  List<SetupWarning> setupWarnings,
                                  boolean checked) {

    /** One failed check: {@code code} is the rule type or a built-in code; {@code ruleId} null for built-ins. */
    public record Issue(String regionKey, String label, Long ruleId, String code, String message) {
    }

    /** A rule that does not fit its field (or names a missing field) and was skipped. */
    public record SetupWarning(Long ruleId, String regionKey, String message) {
    }

    public boolean passed() {
        return errors == null || errors.isEmpty();
    }
}
