package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocDataFieldRule;
import com.modocument.doccreation.entity.DocDataTemplate;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.repo.DocDataFieldRuleJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import com.modocument.doccreation.rules.DocRuleType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rules a PDF template's documents are checked with
 * (docs/doccreation/data-templates-contract.md §4): the rules of its chosen
 * data template, re-keyed onto the regions mapped to each data field, plus
 * the PDF's own extra rules ({@link DocFieldRule}, unchanged).
 *
 * <p>An inherited rule is a TRANSIENT {@link DocFieldRule} (never saved):
 * {@code regionKey} the mapped region, {@code origin} {@link #ORIGIN_DATA_TEMPLATE},
 * the id the NEGATIVE of the data rule's id (so the client never confuses it
 * with a PDF rule), the source flow template and field of the data rule.
 * The {@code Field} of a REQUIRED_IF/COMPARE rule names a data field; it is
 * re-keyed to the first region (mapping order, which is the regions' sort
 * order) mapped to that field, or — when none is — to a key no region can
 * have, so the checks skip it with a setup warning.
 */
@Component
public class EffectiveRules {

    public static final String ORIGIN_DATA_TEMPLATE = "DATA_TEMPLATE";
    /**
     * The {@code Field} of a REQUIRED_IF/COMPARE rule whose other data field
     * is mapped to no region: never a region key ({@code [a-z0-9_]}), so the
     * evaluator reports "names a field this version of the template does not
     * have" (a setup warning). Never shown to the user.
     */
    static final String UNMAPPED_FIELD_PREFIX = "!unmapped:";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAPPING = new TypeReference<>() {
    };

    private final DocFieldRuleJpaRepository pdfRules;
    private final DocDataTemplateJpaRepository dataTemplates;
    private final DocDataFieldRuleJpaRepository dataRules;

    public EffectiveRules(DocFieldRuleJpaRepository pdfRules,
                          DocDataTemplateJpaRepository dataTemplates,
                          DocDataFieldRuleJpaRepository dataRules) {
        this.pdfRules = pdfRules;
        this.dataTemplates = dataTemplates;
        this.dataRules = dataRules;
    }

    /**
     * The resolved rules of a template.
     *
     * @param rules            the inherited rules, then the PDF's own
     * @param inherited        the inherited (DATA_TEMPLATE) rules only
     * @param dataTemplateName the chosen data template's name, null when none is chosen
     */
    public record Resolved(List<DocFieldRule> rules, List<DocFieldRule> inherited, String dataTemplateName) {
    }

    /** The effective rules of {@code blueprint}: the data template's re-keyed, then the PDF's own. */
    public List<DocFieldRule> of(DocBlueprint blueprint) {
        return resolve(blueprint).rules();
    }

    /** {@link #of} with the inherited rules and the data template's name. */
    public Resolved resolve(DocBlueprint blueprint) {
        List<DocFieldRule> own = pdfRules.findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(
                blueprint.getBankId(), blueprint.getDocBlueprintId());
        return resolve(blueprint, own);
    }

    /** {@link #resolve(DocBlueprint)} with the given PDF rules (e.g. unsaved ones under test). */
    public Resolved resolve(DocBlueprint blueprint, List<DocFieldRule> own) {
        Optional<DocDataTemplate> template = dataTemplate(blueprint);
        List<DocFieldRule> inherited = template.isEmpty() ? List.of()
                : rekey(dataRules.findByBankIdAndDataTemplateIdOrderByFieldNameAscSortOrderAscDocDataFieldRuleIdAsc(
                        blueprint.getBankId(), template.get().getDocDataTemplateId()),
                readMapping(blueprint.getDataMappingJson()), blueprint);
        List<DocFieldRule> all = new ArrayList<>(inherited);
        all.addAll(own == null ? List.of() : own);
        return new Resolved(List.copyOf(all), inherited, template.map(DocDataTemplate::getName).orElse(null));
    }

    /** The chosen data template (same bank), empty when none is chosen. */
    public Optional<DocDataTemplate> dataTemplate(DocBlueprint blueprint) {
        if (blueprint.getDataTemplateId() == null) {
            return Optional.empty();
        }
        return dataTemplates.findByDocDataTemplateIdAndBankId(blueprint.getDataTemplateId(), blueprint.getBankId());
    }

    /** The region keys with a REQUIRED rule among {@code rules} ({@code RegionDto.required}). */
    public static Set<String> requiredKeys(List<DocFieldRule> rules) {
        Set<String> keys = new HashSet<>();
        for (DocFieldRule rule : rules) {
            if (DocRuleType.REQUIRED.name().equals(rule.getRuleType())) {
                keys.add(rule.getRegionKey());
            }
        }
        return keys;
    }

    /**
     * The data rules as transient region rules: one copy per region mapped
     * to the rule's field, in mapping order.
     */
    static List<DocFieldRule> rekey(List<DocDataFieldRule> rules, Map<String, String> mapping,
                                    DocBlueprint blueprint) {
        if (rules.isEmpty() || mapping.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> regionsByField = new LinkedHashMap<>();
        mapping.forEach((regionKey, fieldName) ->
                regionsByField.computeIfAbsent(fieldName, f -> new ArrayList<>()).add(regionKey));
        Map<String, List<DocDataFieldRule>> rulesByField = new HashMap<>();
        for (DocDataFieldRule rule : rules) {
            rulesByField.computeIfAbsent(rule.getFieldName(), f -> new ArrayList<>()).add(rule);
        }
        List<DocFieldRule> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            for (DocDataFieldRule rule : rulesByField.getOrDefault(entry.getValue(), List.of())) {
                out.add(copy(rule, entry.getKey(), regionsByField, blueprint));
            }
        }
        return out;
    }

    private static DocFieldRule copy(DocDataFieldRule rule, String regionKey, Map<String, List<String>> regionsByField,
                                     DocBlueprint blueprint) {
        DocFieldRule out = new DocFieldRule();
        out.setDocFieldRuleId(rule.getDocDataFieldRuleId() == null ? null : -rule.getDocDataFieldRuleId());
        out.setBankId(blueprint.getBankId());
        out.setBlueprintId(blueprint.getDocBlueprintId());
        out.setRegionKey(regionKey);
        out.setRuleType(rule.getRuleType());
        out.setConfigJson(rekeyConfig(rule, regionsByField));
        out.setMessage(rule.getMessage());
        out.setSeverity(rule.getSeverity());
        out.setOrigin(ORIGIN_DATA_TEMPLATE);
        out.setSourceTemplateId(rule.getSourceTemplateId());
        out.setSourceFieldName(rule.getSourceFieldName());
        out.setSortOrder(rule.getSortOrder());
        out.setUpdatedAt(rule.getUpdatedAt());
        return out;
    }

    /** REQUIRED_IF/COMPARE: {@code Field} (a data field name) becomes the first region mapped to it. */
    private static String rekeyConfig(DocDataFieldRule rule, Map<String, List<String>> regionsByField) {
        String json = rule.getConfigJson() == null || rule.getConfigJson().isBlank() ? "{}" : rule.getConfigJson();
        if (!DocRuleType.REQUIRED_IF.name().equals(rule.getRuleType())
                && !DocRuleType.COMPARE.name().equals(rule.getRuleType())) {
            return json;
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (!(node instanceof ObjectNode config)) {
                return json;
            }
            JsonNode field = config.get("Field");
            if (field == null || !field.isValueNode() || field.asText().isBlank()) {
                return json;
            }
            List<String> regions = regionsByField.get(field.asText().strip());
            config.put("Field", regions == null || regions.isEmpty()
                    ? UNMAPPED_FIELD_PREFIX + field.asText().strip() : regions.get(0));
            return JSON.writeValueAsString(config);
        } catch (IOException e) {
            return json; // unreadable settings: the evaluator reports it as a setup warning
        }
    }

    // ------------------------------------------------------------- mapping

    /** {@code DocBlueprint.DataMappingJson}: region key to data field name (blank values dropped). */
    public static Map<String, String> readMapping(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, String> mapping = JSON.readValue(json, MAPPING);
            if (mapping == null) {
                return new LinkedHashMap<>();
            }
            mapping.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().isBlank());
            return mapping;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /** The JSON of a mapping; null when it is empty (nothing mapped). */
    public static String writeMapping(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(mapping);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the field mapping.", e);
        }
    }
}
