package com.modocument.application.privacy;

import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.RuleType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Auto-classification (ws12, PRIV-004): infers a field's {@link PiiKind}
 * when the template owner has not tagged it explicitly. Three signals, in
 * order of trust:
 * <ol>
 *   <li><b>Field name</b> — "nic"/"national id" ⇒ NIC, "account"/"acct"/
 *       "iban"/"card number" ⇒ ACCOUNT_NUMBER, "email" ⇒ EMAIL, "phone"/
 *       "mobile"/... ⇒ PHONE, "name"/"surname"/"initial" ⇒ NAME.</li>
 *   <li><b>Validation rules</b> — a REGEX rule whose pattern matches email
 *       addresses (contains a literal {@code @}) ⇒ EMAIL.</li>
 *   <li><b>Value shapes</b> — every non-blank sample matches one of
 *       {@link PiiPatterns}' NIC / account / email shapes (unanimity, so a
 *       mixed free-text column is never misclassified).</li>
 * </ol>
 * No signal ⇒ {@link PiiKind#NONE}. The caller persists the result on the
 * template field (first classification), where it is visible and editable;
 * an explicit tag always wins and is never re-inferred.
 */
@Component
public class PiiClassifier {

    /**
     * "nic" as its own word inside the raw field name ("Customer NIC",
     * "nic_no", "NIC"), never as a mere substring — "electronic" or
     * "Unicode" must not classify a column as a national id.
     */
    private static final Pattern NIC_WORD =
            Pattern.compile("(?i)(?<![a-z0-9])nic(?![a-z0-9])");

    private static final Pattern EMAIL_SHAPE = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** How many leading rows feed the value-shape signal. */
    public static final int SAMPLE_LIMIT = 20;

    /**
     * @param fieldName  the field's (or source column's) name
     * @param fieldRules the field's validation rules, empty when none exist
     * @param samples    sample values from real rows, empty when none exist
     */
    public PiiKind infer(String fieldName, List<ValidationRule> fieldRules,
                         List<String> samples) {
        PiiKind byName = inferFromName(fieldName);
        if (byName != PiiKind.NONE) {
            return byName;
        }
        PiiKind byRules = inferFromRules(fieldRules);
        if (byRules != PiiKind.NONE) {
            return byRules;
        }
        return inferFromSamples(samples);
    }

    private static PiiKind inferFromName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return PiiKind.NONE;
        }
        String normalized = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (NIC_WORD.matcher(fieldName).find() || normalized.contains("nationalid")) {
            return PiiKind.NIC;
        }
        if (normalized.contains("account") || normalized.contains("acct")
                || normalized.contains("iban") || normalized.contains("cardnumber")
                || normalized.contains("creditcard")) {
            return PiiKind.ACCOUNT_NUMBER;
        }
        if (normalized.contains("email")) {
            return PiiKind.EMAIL;
        }
        if (normalized.contains("phone") || normalized.contains("mobile")
                || normalized.contains("msisdn") || normalized.contains("cell")
                || normalized.contains("fax")) {
            return PiiKind.PHONE;
        }
        if (normalized.contains("name") || normalized.contains("surname")
                || normalized.contains("initial")) {
            return PiiKind.NAME;
        }
        return PiiKind.NONE;
    }

    /** An email-shaped REGEX rule (a pattern containing a literal @) ⇒ EMAIL. */
    private static PiiKind inferFromRules(List<ValidationRule> fieldRules) {
        if (fieldRules == null) {
            return PiiKind.NONE;
        }
        for (ValidationRule rule : fieldRules) {
            if (rule.getRuleType() == RuleType.REGEX && rule.getConfigJson() != null
                    && rule.getConfigJson().contains("@")) {
                return PiiKind.EMAIL;
            }
        }
        return PiiKind.NONE;
    }

    /** Unanimous value shapes: every non-blank sample must agree on one kind. */
    private static PiiKind inferFromSamples(List<String> samples) {
        if (samples == null) {
            return PiiKind.NONE;
        }
        PiiKind agreed = null;
        int seen = 0;
        for (String sample : samples) {
            if (sample == null || sample.isBlank()) {
                continue;
            }
            if (++seen > SAMPLE_LIMIT) {
                break;
            }
            PiiKind kind = shapeOf(sample.trim());
            if (kind == PiiKind.NONE) {
                return PiiKind.NONE;
            }
            if (agreed == null) {
                agreed = kind;
            } else if (agreed != kind) {
                return PiiKind.NONE;
            }
        }
        return agreed == null ? PiiKind.NONE : agreed;
    }

    private static PiiKind shapeOf(String value) {
        if (PiiPatterns.NIC.matcher(value).matches()) {
            return PiiKind.NIC;
        }
        if (EMAIL_SHAPE.matcher(value).matches()) {
            return PiiKind.EMAIL;
        }
        if (PiiPatterns.ACCOUNT.matcher(value).matches()) {
            return PiiKind.ACCOUNT_NUMBER;
        }
        return PiiKind.NONE;
    }
}
