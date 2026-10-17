package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.TemplateRepository;
import com.modocument.doccreation.dto.FieldChecksDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSource;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.repo.DocBlueprintSourceJpaRepository;
import com.modocument.doccreation.rules.DocRuleType;
import com.modocument.doccreation.rules.RuleSummaries;
import com.modocument.doccreation.rules.RuleTypeCatalog;
import com.modocument.domain.entity.Template;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds {@code InstanceDto.fieldChecks} (field-validation-contract §4.4):
 * for every field of a document's version, the template's rules with a
 * plain-language summary (rules that do not fit the field's kind are left
 * out, as the checks skip them), the checks its kind and properties imply,
 * and the flow fields its data sources map onto it. Template data only; a
 * document value is never read here.
 */
@Component
public class FieldChecksBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAPPING = new TypeReference<>() {
    };

    private final DocBlueprintSourceJpaRepository sources;
    private final TemplateRepository templates;

    public FieldChecksBuilder(DocBlueprintSourceJpaRepository sources, TemplateRepository templates) {
        this.sources = sources;
        this.templates = templates;
    }

    public Map<String, FieldChecksDto> build(DocBlueprint blueprint, List<TemplateRegion> regions,
                                             Map<String, FieldProps> props, List<DocFieldRule> rules) {
        return build(blueprint, regions, props, rules, null);
    }

    /**
     * ws34 (data-templates-contract §4): {@code rules} are the effective
     * rules; an inherited rule (origin DATA_TEMPLATE) shows the data
     * template's name ({@code dataTemplateName}) as its
     * {@code sourceTemplateName}, with the data rule's source flow field.
     */
    public Map<String, FieldChecksDto> build(DocBlueprint blueprint, List<TemplateRegion> regions,
                                             Map<String, FieldProps> props, List<DocFieldRule> rules,
                                             String dataTemplateName) {
        Map<Integer, String> names = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        Map<String, RegionKind> kinds = new HashMap<>();
        for (TemplateRegion r : regions) {
            labels.put(r.key(), r.label() == null || r.label().isBlank() ? r.key() : r.label());
            kinds.put(r.key(), r.kind());
        }

        Map<String, List<FieldChecksDto.RuleView>> ruleViews = new HashMap<>();
        for (DocFieldRule rule : rules) {
            RegionKind kind = kinds.get(rule.getRegionKey());
            Optional<DocRuleType> type = DocRuleType.parse(rule.getRuleType());
            if (kind == null || type.isEmpty() || !RuleTypeCatalog.fits(type.get(), kind)) {
                continue;
            }
            boolean imported = "IMPORTED".equals(rule.getOrigin());
            boolean inherited = EffectiveRules.ORIGIN_DATA_TEMPLATE.equals(rule.getOrigin());
            String sourceTemplate = inherited ? dataTemplateName
                    : imported && rule.getSourceTemplateId() != null ? templateName(rule.getSourceTemplateId(), names)
                    : null;
            ruleViews.computeIfAbsent(rule.getRegionKey(), k -> new ArrayList<>())
                    .add(new FieldChecksDto.RuleView(rule.getDocFieldRuleId(), type.get().name(),
                            RuleSummaries.summary(type.get(), config(rule.getConfigJson()), labels),
                            rule.getSeverity(), rule.getOrigin(), sourceTemplate,
                            imported || inherited ? rule.getSourceFieldName() : null));
        }

        Map<String, List<FieldChecksDto.MappedFrom>> mapped = new HashMap<>();
        for (DocBlueprintSource source : sources
                .findByBankIdAndBlueprintIdOrderBySortOrderAscDocBlueprintSourceIdAsc(
                        blueprint.getBankId(), blueprint.getDocBlueprintId())) {
            String name = templateName(source.getTemplateId(), names);
            mapping(source.getMappingJson()).forEach((regionKey, fieldName) -> {
                if (fieldName != null && !fieldName.isBlank()) {
                    mapped.computeIfAbsent(regionKey, k -> new ArrayList<>())
                            .add(new FieldChecksDto.MappedFrom(source.getTemplateId(), name, fieldName,
                                    source.getRole()));
                }
            });
        }

        Map<String, FieldChecksDto> out = new LinkedHashMap<>();
        for (TemplateRegion r : regions) {
            out.put(r.key(), new FieldChecksDto(
                    List.copyOf(ruleViews.getOrDefault(r.key(), List.of())),
                    builtIn(r.kind(), props.getOrDefault(r.key(), FieldProps.NONE)),
                    List.copyOf(mapped.getOrDefault(r.key(), List.of()))));
        }
        return out;
    }

    /** The checks a field's kind and properties imply (the built-in checks of contract §4.2). */
    static List<String> builtIn(RegionKind kind, FieldProps props) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case DATE -> out.add("A date");
            case NUMBER -> out.add("A number");
            case CHOICE -> out.add("One of its options");
            default -> {
            }
        }
        if (props != null && props.maxLength() != null) {
            out.add("At most " + props.maxLength() + " characters");
        }
        return List.copyOf(out);
    }

    private String templateName(int templateId, Map<Integer, String> names) {
        return names.computeIfAbsent(templateId,
                id -> templates.getById(id).map(Template::getName).orElse("a removed flow template"));
    }

    private static JsonNode config(String json) {
        try {
            return json == null || json.isBlank() ? null : JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> mapping(String json) {
        try {
            Map<String, String> m = json == null || json.isBlank() ? null : JSON.readValue(json, MAPPING);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
