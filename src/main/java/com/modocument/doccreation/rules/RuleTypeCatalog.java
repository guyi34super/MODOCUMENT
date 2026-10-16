package com.modocument.doccreation.rules;

import com.modocument.doccreation.pdftemplate.RegionKind;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which field kinds each {@link DocRuleType} applies to and which config
 * keys it takes (docs/doccreation/field-validation-contract.md §3; the
 * {@code ruleTypes} of {@code GET /blueprints/{id}/rules}). A rule on a kind
 * it does not fit is kept but skipped when a document is validated (a setup
 * warning). IMAGE and SIGNATURE fields take REQUIRED only; a CHECKBOX takes
 * REQUIRED and REQUIRED_IF.
 */
public final class RuleTypeCatalog {

    /** One rule type: the kinds it applies to (the most natural first) and its config keys. */
    public record Entry(DocRuleType type, List<RegionKind> kinds, List<String> configKeys) {
    }

    private static final Map<DocRuleType, Entry> ENTRIES = build();

    private RuleTypeCatalog() {
    }

    /** Every rule type, in {@link DocRuleType} order. */
    public static List<Entry> all() {
        return List.copyOf(ENTRIES.values());
    }

    public static Entry of(DocRuleType type) {
        return ENTRIES.get(type);
    }

    /** Whether a rule of {@code type} applies to a field of {@code kind}. */
    public static boolean fits(DocRuleType type, RegionKind kind) {
        return kind != null && ENTRIES.get(type).kinds().contains(kind);
    }

    private static Map<DocRuleType, Entry> build() {
        RegionKind text = RegionKind.TEXT;
        RegionKind multiline = RegionKind.MULTILINE;
        RegionKind date = RegionKind.DATE;
        RegionKind number = RegionKind.NUMBER;
        RegionKind checkbox = RegionKind.CHECKBOX;
        RegionKind choice = RegionKind.CHOICE;
        Map<DocRuleType, Entry> m = new EnumMap<>(DocRuleType.class);
        put(m, DocRuleType.REQUIRED, List.of(RegionKind.values()), List.of());
        put(m, DocRuleType.REGEX, List.of(text, multiline, date, number, choice), List.of("Pattern"));
        put(m, DocRuleType.EMAIL, List.of(text, multiline), List.of());
        put(m, DocRuleType.LENGTH, List.of(text, multiline, number, choice), List.of("Min", "Max"));
        put(m, DocRuleType.RANGE, List.of(number, text), List.of("Min", "Max"));
        put(m, DocRuleType.ALLOWED_VALUES, List.of(text, number, choice), List.of("Values", "IgnoreCase"));
        put(m, DocRuleType.DATE_FORMAT, List.of(date, text), List.of("Formats"));
        put(m, DocRuleType.DATE_RANGE, List.of(date, text), List.of("Min", "Max"));
        put(m, DocRuleType.EXPIRY_DATE, List.of(date, text), List.of());
        put(m, DocRuleType.REQUIRED_IF, List.of(text, multiline, date, number, checkbox, choice),
                List.of("Field", "Equals"));
        put(m, DocRuleType.COMPARE, List.of(text, date, number, choice), List.of("Field", "Op"));
        return m;
    }

    private static void put(Map<DocRuleType, Entry> m, DocRuleType type, List<RegionKind> kinds,
                            List<String> configKeys) {
        m.put(type, new Entry(type, List.copyOf(kinds), List.copyOf(configKeys)));
    }
}
