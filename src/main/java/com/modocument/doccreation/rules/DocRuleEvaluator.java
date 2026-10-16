package com.modocument.doccreation.rules;

import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.entity.DocFieldRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks values against a template's fields and rules
 * (field-validation-contract §3/§4.2): the built-in kind checks of every
 * region, then the rules of the keys present in {@code regions}. Pure: no
 * persistence, no audit; the caller sets state/validatedAt/checked.
 * Implemented by BE-A; BE-B's {@code /rules/test} calls it.
 */
public interface DocRuleEvaluator {

    /**
     * @param regions         the document version's fields
     * @param values          region key to value (blank = absent)
     * @param placedImageKeys IMAGE/SIGNATURE keys that hold a photo
     * @param rules           the blueprint's rules (any keys; unknown keys are setup warnings)
     * @param rulesRevision   the blueprint's RulesRevision, echoed in the result
     * @return errors, warnings and setup warnings; {@code state} DRAFT or VALIDATED by errors,
     *         {@code checked} true, {@code validatedAt} null
     */
    ValidationResultDto evaluate(List<RegionView> regions, Map<String, String> values,
                                 Set<String> placedImageKeys, List<DocFieldRule> rules, int rulesRevision);
}
