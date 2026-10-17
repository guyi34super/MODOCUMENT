package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.application.validation.RegexPatternChecker;
import com.modocument.application.validation.RegexValidationErrorMessages;
import com.modocument.doccreation.dto.ImportPreviewDto;
import com.modocument.doccreation.dto.ImportRulesRequest;
import com.modocument.doccreation.dto.RulesDto;
import com.modocument.doccreation.dto.SaveRulesRequest;
import com.modocument.doccreation.dto.TestRulesRequest;
import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSource;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import com.modocument.doccreation.rules.DocRuleEvaluator;
import com.modocument.doccreation.rules.DocRuleType;
import com.modocument.doccreation.rules.RegionView;
import com.modocument.doccreation.rules.RuleTypeCatalog;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.RuleType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The field rules of a PDF template (docs/doccreation/field-validation-contract.md
 * §3): read, replace the whole set, test sample values, and import rules
 * from flow-template field rules (the template's saved data sources or one
 * chosen flow template).
 *
 * <p>The caller must own the template ({@link DocCreationOwnership}, the
 * same as the overlay); an import also needs {@code validation:read} (the
 * route asks for {@code doc:write}) and, per flow template, read access
 * ({@link TemplateOwnership}). Every change bumps the template's
 * RulesRevision under a row lock; a save names the revision it was based on
 * (409 when stale). Audit records counts only, never configs or values.
 *
 * <p>The REQUIRED rules are the single source of truth for a field's
 * required flag: {@link #requiredKeys} derives {@code RegionDto.required},
 * and {@link #syncRequiredFlags} keeps the rules in step with the old
 * Required checkbox of an overlay save.
 */
@Service
public class DocFieldRuleUseCase {

    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_IMPORTED = "IMPORTED";
    public static final String ORIGIN_REGION_FLAG = "REGION_FLAG";

    /**
     * The flow UI's EMAIL preset ({@code EMAIL_PATTERN} in
     * frontend/src/api/validationRules.js): a flow REGEX rule with exactly
     * this pattern is imported as an EMAIL rule.
     */
    public static final String FLOW_EMAIL_PATTERN = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    static final String SOURCE_DATA_SOURCES = "DATA_SOURCES";
    static final String SOURCE_TEMPLATE = "TEMPLATE";
    static final String CANNOT_IMPORT = "can't be imported";
    static final int MAX_TEST_VALUES = 2000;
    static final int MAX_TEST_VALUE_LENGTH = 20000;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final DocCreationOwnership ownership;
    private final DocFieldRuleJpaRepository rules;
    private final TemplateVersions versions;
    private final BlueprintSourceUseCase sources;
    private final TemplateOwnership templateOwnership;
    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;
    private final BlueprintMappingSuggester suggester;
    private final ValidationRuleRepository flowRules;
    private final AuditRecorder audit;
    private final ObjectProvider<DocRuleEvaluator> evaluator;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * ws34 (data-templates-contract §4): the data template's rules re-keyed
     * onto the mapped regions; null in unit wiring (the PDF's own rules only).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EffectiveRules effectiveRules;

    public DocFieldRuleUseCase(DocCreationOwnership ownership,
                               DocFieldRuleJpaRepository rules,
                               TemplateVersions versions,
                               BlueprintSourceUseCase sources,
                               TemplateOwnership templateOwnership,
                               TemplateRepository templates,
                               CurrentUserProvider currentUser,
                               BlueprintMappingSuggester suggester,
                               ValidationRuleRepository flowRules,
                               AuditRecorder audit,
                               ObjectProvider<DocRuleEvaluator> evaluator) {
        this.ownership = ownership;
        this.rules = rules;
        this.versions = versions;
        this.sources = sources;
        this.templateOwnership = templateOwnership;
        this.templates = templates;
        this.currentUser = currentUser;
        this.suggester = suggester;
        this.flowRules = flowRules;
        this.audit = audit;
        this.evaluator = evaluator;
    }

    // ---------------------------------------------------------------- API

    /**
     * {@code GET /blueprints/{id}/rules}: the PDF's own rules, plus (ws34)
     * the data template's rules re-keyed onto the mapped regions as
     * {@code inherited} (read-only; never part of a save).
     */
    @Transactional(readOnly = true)
    public RulesDto get(int blueprintId) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        return toDto(blueprint, rulesOf(blueprint));
    }

    /**
     * {@code PUT /blueprints/{id}/rules}: replaces the whole rule set.
     *
     * @throws IllegalStateException    when {@code baseRevision} is stale (409)
     * @throws IllegalArgumentException when a rule fails a check (400)
     */
    @Transactional
    public RulesDto save(int blueprintId, SaveRulesRequest request) {
        DocBlueprint blueprint = lock(ownership.requireTemplate(blueprintId));
        if (request == null) {
            throw new IllegalArgumentException("The rules are required.");
        }
        requireCurrentRevision(blueprint, request.baseRevision());
        List<DocFieldRule> checked = FieldRuleChecks.check(request.rules(), currentKeys(blueprint));
        rules.deleteBlueprint(blueprint.getBankId(), blueprintId);
        LocalDateTime now = LocalDateTime.now();
        for (DocFieldRule rule : checked) {
            stamp(rule, blueprint, now);
        }
        List<DocFieldRule> saved = rules.saveAll(checked);
        bump(blueprint);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("revision", blueprint.getRulesRevision());
        values.put("rules", saved.size());
        values.put("byType", countBy(saved, DocFieldRule::getRuleType));
        values.put("bySeverity", countBy(saved, DocFieldRule::getSeverity));
        audit.success(AuditActions.DOC_FIELD_RULES_SAVED, "DocBlueprint", String.valueOf(blueprintId), null,
                values);
        return toDto(blueprint, rulesOf(blueprint));
    }

    /**
     * {@code POST /blueprints/{id}/rules/test}: checks sample values against
     * the given (unsaved) rules or the saved ones — ws34: together with the
     * data template's rules re-keyed onto the mapped regions (the effective
     * rules). Nothing is stored.
     */
    @Transactional(readOnly = true)
    public ValidationResultDto test(int blueprintId, TestRulesRequest request) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        Map<String, String> values = testValues(request == null ? null : request.values());
        List<DocFieldRule> checkedRules;
        if (request != null && request.rules() != null) {
            checkedRules = FieldRuleChecks.check(request.rules(), currentKeys(blueprint));
            checkedRules.forEach(rule -> stamp(rule, blueprint, null));
        } else {
            checkedRules = rulesOf(blueprint);
        }
        if (effectiveRules != null) {
            checkedRules = effectiveRules.resolve(blueprint, checkedRules).rules();
        }
        DocRuleEvaluator checker = evaluator.getIfAvailable();
        if (checker == null) {
            throw new DocCreationException(503, "RULES_UNAVAILABLE",
                    "Field rules cannot be tested right now; try again later.");
        }
        int version = TemplateVersions.effectiveVersion(blueprint);
        Map<String, FieldProps> props = versions.fieldProps(blueprint, version);
        List<RegionView> regions = versions.regions(blueprint, version).stream()
                .sorted(Comparator.comparingInt(TemplateRegion::sortOrder))
                .map(r -> new RegionView(r.key(), r.label(), r.kind(), props.get(r.key())))
                .toList();
        return checker.evaluate(regions, values, Set.of(), checkedRules, blueprint.getRulesRevision());
    }

    /** {@code GET /blueprints/{id}/rules/import-preview?templateId=}. */
    @Transactional(readOnly = true)
    public ImportPreviewDto importPreview(int blueprintId, Integer templateId) {
        requireValidationRead();
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        Plan plan = plan(blueprint, templateId, null);
        List<ImportPreviewDto.Field> fields = plan.fields().stream()
                .map(f -> new ImportPreviewDto.Field(f.regionKey(), f.label(), f.fieldName(), f.score(),
                        f.sameName(), f.templateId(), f.templateName(),
                        f.rules().stream().map(r -> new ImportPreviewDto.PreviewRule(r.type().name(), r.config(),
                                r.message())).toList(),
                        f.skipped()))
                .toList();
        return new ImportPreviewDto(plan.source(), plan.templateId(), plan.templateName(), fields,
                plan.templateFields());
    }

    /**
     * {@code POST /blueprints/{id}/rules/import}: MERGE replaces only the
     * rules of the same field, type and source template; REPLACE first
     * deletes every IMPORTED rule of the mapped fields. MANUAL and
     * REGION_FLAG rules are always kept. A rule identical to one the field
     * keeps (same type and settings) is not added twice.
     */
    @Transactional
    public RulesDto importRules(int blueprintId, ImportRulesRequest request) {
        requireValidationRead();
        DocBlueprint blueprint = lock(ownership.requireTemplate(blueprintId));
        if (request == null) {
            throw new IllegalArgumentException("An import request is required.");
        }
        requireCurrentRevision(blueprint, request.baseRevision());
        boolean replace = parseMode(request.mode());
        Plan plan = plan(blueprint, request.templateId(), request.mapping());

        List<DocFieldRule> existing = rulesOf(blueprint);
        List<DocFieldRule> kept = new ArrayList<>(existing);
        List<DocFieldRule> removed = new ArrayList<>();
        if (replace) {
            Set<String> mappedKeys = plan.fields().stream().map(PlannedField::regionKey)
                    .collect(Collectors.toSet());
            for (DocFieldRule rule : existing) {
                if (ORIGIN_IMPORTED.equals(rule.getOrigin()) && mappedKeys.contains(rule.getRegionKey())) {
                    removed.add(rule);
                }
            }
        } else {
            Set<String> incoming = new HashSet<>();
            for (PlannedField field : plan.fields()) {
                for (Converted rule : field.rules()) {
                    incoming.add(field.regionKey() + "\u0000" + rule.type().name() + "\u0000" + field.templateId());
                }
            }
            for (DocFieldRule rule : existing) {
                if (rule.getSourceTemplateId() != null && incoming.contains(rule.getRegionKey() + "\u0000"
                        + rule.getRuleType() + "\u0000" + rule.getSourceTemplateId())) {
                    removed.add(rule);
                }
            }
        }
        kept.removeAll(removed);

        LocalDateTime now = LocalDateTime.now();
        List<DocFieldRule> added = new ArrayList<>();
        int duplicates = 0;
        int skipped = 0;
        for (PlannedField field : plan.fields()) {
            skipped += field.skipped().size();
            for (Converted converted : field.rules()) {
                String configJson = converted.config().toString();
                boolean present = kept.stream().anyMatch(r -> r.getRegionKey().equals(field.regionKey())
                        && r.getRuleType().equals(converted.type().name())
                        && FieldRuleChecks.readConfig(r.getConfigJson()).equals(converted.config()));
                if (present) {
                    duplicates++;
                    continue;
                }
                DocFieldRule rule = new DocFieldRule();
                rule.setRegionKey(field.regionKey());
                rule.setRuleType(converted.type().name());
                rule.setConfigJson(configJson);
                rule.setMessage(converted.message());
                rule.setSeverity("ERROR");
                rule.setOrigin(ORIGIN_IMPORTED);
                rule.setSourceTemplateId(field.templateId());
                rule.setSourceFieldName(FieldRuleChecks.truncate(field.fieldName(), FieldRuleChecks.MAX_SOURCE_FIELD));
                rule.setSortOrder(kept.stream().filter(r -> r.getRegionKey().equals(field.regionKey()))
                        .mapToInt(DocFieldRule::getSortOrder).max().orElse(-1) + 1);
                stamp(rule, blueprint, now);
                kept.add(rule);
                added.add(rule);
            }
        }
        FieldRuleChecks.checkLimits(kept);
        rules.deleteAll(removed);
        rules.flush();
        rules.saveAll(added);
        bump(blueprint);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("source", plan.source());
        if (plan.templateId() != null) {
            values.put("templateId", plan.templateId());
        }
        values.put("mode", replace ? "REPLACE" : "MERGE");
        values.put("fields", plan.fields().size());
        values.put("imported", added.size());
        values.put("replaced", removed.size());
        values.put("skipped", skipped);
        values.put("duplicates", duplicates);
        values.put("skippedFields", plan.skippedFields());
        values.put("revision", blueprint.getRulesRevision());
        audit.success(AuditActions.DOC_FIELD_RULES_IMPORTED, "DocBlueprint", String.valueOf(blueprintId), null,
                values);
        return toDto(blueprint, rulesOf(blueprint));
    }

    // ------------------------------------------------- shared with templates

    /**
     * The region keys with a REQUIRED rule: {@code RegionDto.required}
     * (contract §3) — ws34: among the effective rules, so a field the data
     * template requires is shown required too.
     */
    public Set<String> requiredKeys(DocBlueprint blueprint) {
        return EffectiveRules.requiredKeys(effectiveRules == null ? rulesOf(blueprint)
                : effectiveRules.resolve(blueprint, rulesOf(blueprint)).rules());
    }

    /**
     * Keeps the REQUIRED rules in step with the regions' Required flag of an
     * overlay save (or an upload): {@code required:true} without a REQUIRED
     * rule adds one ({@code origin}), {@code required:false} removes the
     * MANUAL and REGION_FLAG REQUIRED rules of that key (IMPORTED ones stay).
     * When anything changed, the blueprint's RulesRevision goes up by one on
     * the entity; the caller saves it (and should hold {@link #lock}).
     * ws34: a key the data template already requires (an inherited REQUIRED
     * rule, shown as {@code required:true}) gets no PDF rule of its own.
     *
     * @return whether a rule was added or removed
     */
    public boolean syncRequiredFlags(DocBlueprint blueprint, List<TemplateRegion> regions, String origin) {
        List<DocFieldRule> all = rulesOf(blueprint);
        Map<String, List<DocFieldRule>> requiredByKey = new HashMap<>();
        Map<String, Integer> maxSort = new HashMap<>();
        for (DocFieldRule rule : all) {
            maxSort.merge(rule.getRegionKey(), rule.getSortOrder(), Math::max);
            if (DocRuleType.REQUIRED.name().equals(rule.getRuleType())) {
                requiredByKey.computeIfAbsent(rule.getRegionKey(), k -> new ArrayList<>()).add(rule);
            }
        }
        Set<String> inheritedRequired = effectiveRules == null ? Set.of()
                : EffectiveRules.requiredKeys(effectiveRules.resolve(blueprint, List.of()).inherited());
        List<DocFieldRule> added = new ArrayList<>();
        List<DocFieldRule> removed = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (TemplateRegion region : regions) {
            List<DocFieldRule> required = requiredByKey.getOrDefault(region.key(), List.of());
            if (region.required()) {
                if (required.isEmpty() && !inheritedRequired.contains(region.key())) {
                    DocFieldRule rule = new DocFieldRule();
                    rule.setRegionKey(region.key());
                    rule.setRuleType(DocRuleType.REQUIRED.name());
                    rule.setConfigJson("{}");
                    rule.setSeverity("ERROR");
                    rule.setOrigin(origin);
                    rule.setSortOrder(maxSort.getOrDefault(region.key(), -1) + 1);
                    stamp(rule, blueprint, now);
                    added.add(rule);
                }
            } else {
                for (DocFieldRule rule : required) {
                    if (ORIGIN_MANUAL.equals(rule.getOrigin()) || ORIGIN_REGION_FLAG.equals(rule.getOrigin())) {
                        removed.add(rule);
                    }
                }
            }
        }
        if (added.isEmpty() && removed.isEmpty()) {
            return false;
        }
        rules.deleteAll(removed);
        rules.saveAll(added);
        blueprint.setRulesRevision(blueprint.getRulesRevision() + 1);
        return true;
    }

    /**
     * Locks the template's row for this transaction (SELECT ... FOR UPDATE)
     * and refreshes it, so rule saves, imports and overlay saves of one
     * template run one after the other and RulesRevision is never lost.
     */
    public DocBlueprint lock(DocBlueprint blueprint) {
        if (entityManager != null && entityManager.contains(blueprint)) {
            entityManager.refresh(blueprint, LockModeType.PESSIMISTIC_WRITE);
        }
        return blueprint;
    }

    /** Deletes a template's rules (the template is being deleted). */
    public void deleteBlueprint(int bankId, int blueprintId) {
        rules.deleteBlueprint(bankId, blueprintId);
    }

    // ------------------------------------------------------------- import

    /** One flow rule converted to a field rule. */
    record Converted(DocRuleType type, ObjectNode config, String message) {
    }

    /** One region and the flow field it takes rules from. */
    record PlannedField(String regionKey, String label, RegionKind kind, String fieldName, Double score,
                        boolean sameName, Integer templateId, String templateName, List<Converted> rules,
                        List<ImportPreviewDto.Skipped> skipped) {
    }

    record Plan(String source, Integer templateId, String templateName, List<PlannedField> fields,
                List<String> templateFields, int skippedFields) {
    }

    private Plan plan(DocBlueprint blueprint, Integer templateId, Map<String, String> clientMapping) {
        List<TemplateRegion> regions = currentRegions(blueprint);
        Map<String, TemplateRegion> byKey = regions.stream()
                .collect(Collectors.toMap(TemplateRegion::key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, String> requested = cleanMapping(clientMapping);
        List<PlannedField> fields = new ArrayList<>();
        int skippedFields = 0;
        if (templateId == null) {
            Set<String> templateFields = new LinkedHashSet<>();
            for (DocBlueprintSource source : sources.sourcesOf(blueprint)) {
                Optional<Template> template = templates.getAccessible(source.getTemplateId(),
                        currentUser.requireUserId(), currentUser.requireBankId());
                if (template.isEmpty()) {
                    continue;
                }
                List<BlueprintMappingSuggester.Field> flowFields = suggester.fields(source.getTemplateId());
                flowFields.forEach(f -> templateFields.add(f.name()));
                Set<String> fieldNames = flowFields.stream().map(BlueprintMappingSuggester.Field::name)
                        .collect(Collectors.toSet());
                Map<String, List<ValidationRule>> byField = flowRulesByField(source.getTemplateId());
                for (Map.Entry<String, String> entry : BlueprintSourceUseCase.readMapping(source).entrySet()) {
                    TemplateRegion region = byKey.get(entry.getKey());
                    String fieldName = entry.getValue();
                    if (requested != null) {
                        String wanted = requested.get(entry.getKey());
                        if (!requested.containsKey(entry.getKey())
                                || wanted != null && !wanted.isEmpty() && !wanted.equals(fieldName)) {
                            continue;
                        }
                    }
                    if (region == null || !fieldNames.contains(fieldName)) {
                        skippedFields++;
                        continue;
                    }
                    fields.add(planned(region, fieldName, null,
                            RecordText.sameName(fieldName, region.key(), region.label()),
                            template.get(), byField.getOrDefault(fieldName, List.of())));
                }
            }
            return new Plan(SOURCE_DATA_SOURCES, null, null, fields, List.copyOf(templateFields), skippedFields);
        }

        Template template = templateOwnership.requireReadable(templateId);
        List<BlueprintMappingSuggester.Field> flowFields = suggester.fields(template.getTemplateId());
        Set<String> fieldNames = flowFields.stream().map(BlueprintMappingSuggester.Field::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<ValidationRule>> byField = flowRulesByField(template.getTemplateId());
        if (requested == null) {
            Map<String, BlueprintMappingSuggester.Candidate> matched = new HashMap<>();
            for (BlueprintMappingSuggester.Candidate c : suggester.suggestions(regions, flowFields, Set.of())) {
                matched.put(c.regionKey(), c);
            }
            for (TemplateRegion region : regions) {
                BlueprintMappingSuggester.Candidate c = matched.get(region.key());
                if (c != null) {
                    fields.add(planned(region, c.fieldName(), c.score(), c.sameName(), template,
                            byField.getOrDefault(c.fieldName(), List.of())));
                }
            }
        } else {
            for (Map.Entry<String, String> entry : requested.entrySet()) {
                TemplateRegion region = byKey.get(entry.getKey());
                String fieldName = entry.getValue();
                if (fieldName == null || fieldName.isEmpty()) {
                    continue;
                }
                if (region == null || !fieldNames.contains(fieldName)
                        || region.kind() == RegionKind.IMAGE || region.kind() == RegionKind.SIGNATURE) {
                    skippedFields++;
                    continue;
                }
                fields.add(planned(region, fieldName, null,
                        RecordText.sameName(fieldName, region.key(), region.label()), template,
                        byField.getOrDefault(fieldName, List.of())));
            }
        }
        return new Plan(SOURCE_TEMPLATE, template.getTemplateId(), template.getName(), fields,
                List.copyOf(fieldNames), skippedFields);
    }

    private PlannedField planned(TemplateRegion region, String fieldName, Double score, boolean sameName,
                                 Template template, List<ValidationRule> flowRulesOfField) {
        List<Converted> converted = new ArrayList<>();
        List<ImportPreviewDto.Skipped> skipped = new ArrayList<>();
        for (ValidationRule flowRule : flowRulesOfField) {
            String flowType = flowRule.getRuleType() == null ? "UNKNOWN" : flowRule.getRuleType().name();
            Converted rule;
            try {
                rule = convert(flowRule);
            } catch (IllegalArgumentException e) {
                skipped.add(new ImportPreviewDto.Skipped(flowType, e.getMessage()));
                continue;
            }
            if (!RuleTypeCatalog.fits(rule.type(), region.kind())) {
                skipped.add(new ImportPreviewDto.Skipped(flowType, rule.type().name() + " does not apply to a "
                        + region.kind().name().toLowerCase(Locale.ROOT) + " field"));
                continue;
            }
            converted.add(rule);
        }
        return new PlannedField(region.key(), region.label(), region.kind(), fieldName, score, sameName,
                template.getTemplateId(), template.getName(), converted, skipped);
    }

    /**
     * One flow rule as a field rule (contract §3 conversion table).
     *
     * @throws IllegalArgumentException with the reason it is skipped
     */
    static Converted convert(ValidationRule flowRule) {
        RuleType type = flowRule.getRuleType();
        if (type == null) {
            throw new IllegalArgumentException(CANNOT_IMPORT);
        }
        ObjectNode config = FieldRuleChecks.readConfig(flowRule.getConfigJson());
        return switch (type) {
            case REQUIRED -> new Converted(DocRuleType.REQUIRED, NODES.objectNode(), null);
            case EXPIRY_DATE -> new Converted(DocRuleType.EXPIRY_DATE, NODES.objectNode(), null);
            case DATE_FORMAT -> {
                ObjectNode out = NODES.objectNode();
                out.putArray("Formats").add("yyyy-MM-dd");
                yield new Converted(DocRuleType.DATE_FORMAT, out, null);
            }
            case REGEX -> {
                JsonNode patternNode = config.get("Pattern");
                String pattern = patternNode == null || !patternNode.isValueNode() ? null : patternNode.asText();
                if (RegexPatternChecker.check(pattern) != null) {
                    throw new IllegalArgumentException("its pattern is not valid");
                }
                if (FLOW_EMAIL_PATTERN.equals(pattern)) {
                    yield new Converted(DocRuleType.EMAIL, NODES.objectNode(), null);
                }
                ObjectNode out = NODES.objectNode();
                out.put("Pattern", pattern);
                yield new Converted(DocRuleType.REGEX, out, regexMessage(flowRule.getFieldName(), pattern));
            }
            case RANGE -> {
                BigDecimal min = decimal(config.get("Min"));
                BigDecimal max = decimal(config.get("Max"));
                if (min == null && max == null || min != null && max != null && min.compareTo(max) > 0) {
                    throw new IllegalArgumentException("its range is not valid");
                }
                ObjectNode out = NODES.objectNode();
                if (min != null) {
                    out.put("Min", min);
                }
                if (max != null) {
                    out.put("Max", max);
                }
                yield new Converted(DocRuleType.RANGE, out, null);
            }
            case CONDITIONAL_REQUIREMENT, TYPE, CUSTOM -> throw new IllegalArgumentException(CANNOT_IMPORT);
        };
    }

    /**
     * The flow's own message for a REGEX rule of {@code sourceFieldName}
     * (the Dayforce/PowerPay wording), or null when it would only be the
     * generic "Validation error for X." text.
     */
    static String regexMessage(String sourceFieldName, String pattern) {
        if (sourceFieldName == null) {
            return null;
        }
        String message = RegexValidationErrorMessages
                .getRegexValidationErrorMessage(0, sourceFieldName, null, pattern).errorMessage();
        if (message == null || message.startsWith("Validation error for " + sourceFieldName + ".")) {
            return null;
        }
        String clean = message.strip();
        return clean.length() <= FieldRuleChecks.MAX_MESSAGE ? clean
                : clean.substring(0, FieldRuleChecks.MAX_MESSAGE - 1).strip() + "…";
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("its range is not valid");
        }
    }

    private Map<String, List<ValidationRule>> flowRulesByField(int templateId) {
        Map<String, List<ValidationRule>> byField = new HashMap<>();
        for (ValidationRule rule : flowRules.getRulesByTemplate(templateId)) {
            byField.computeIfAbsent(rule.getFieldName(), k -> new ArrayList<>()).add(rule);
        }
        byField.values().forEach(list -> list.sort(Comparator.comparingInt(
                r -> r.getRuleType() == null ? Integer.MAX_VALUE : r.getRuleType().ordinal())));
        return byField;
    }

    private static Map<String, String> cleanMapping(Map<String, String> mapping) {
        if (mapping == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        mapping.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                out.put(key.strip(), value == null ? null : value.strip());
            }
        });
        return out;
    }

    private static boolean parseMode(String mode) {
        if (mode == null || mode.isBlank() || "MERGE".equalsIgnoreCase(mode.strip())) {
            return false;
        }
        if ("REPLACE".equalsIgnoreCase(mode.strip())) {
            return true;
        }
        throw new IllegalArgumentException("mode must be MERGE or REPLACE.");
    }

    // ------------------------------------------------------------ helpers

    private void requireValidationRead() {
        if (!ownership.callerHas(Permission.VALIDATION_READ)) {
            throw new AccessDeniedException("Importing field rules needs validation:read.");
        }
    }

    private static void requireCurrentRevision(DocBlueprint blueprint, Integer baseRevision) {
        if (baseRevision != null && baseRevision != blueprint.getRulesRevision()) {
            throw new IllegalStateException("The field rules were changed meanwhile (they are now at revision "
                    + blueprint.getRulesRevision() + "). Reload them and make your changes again.");
        }
    }

    private static void bump(DocBlueprint blueprint) {
        blueprint.setRulesRevision(blueprint.getRulesRevision() + 1);
    }

    private static void stamp(DocFieldRule rule, DocBlueprint blueprint, LocalDateTime now) {
        rule.setBankId(blueprint.getBankId());
        rule.setBlueprintId(blueprint.getDocBlueprintId());
        rule.setUpdatedAt(now);
    }

    private List<DocFieldRule> rulesOf(DocBlueprint blueprint) {
        return new ArrayList<>(rules.findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(
                blueprint.getBankId(), blueprint.getDocBlueprintId()));
    }

    private List<TemplateRegion> currentRegions(DocBlueprint blueprint) {
        List<TemplateRegion> regions = new ArrayList<>(
                versions.regions(blueprint, TemplateVersions.effectiveVersion(blueprint)));
        regions.sort(Comparator.comparingInt(TemplateRegion::sortOrder));
        return regions;
    }

    private Set<String> currentKeys(DocBlueprint blueprint) {
        return currentRegions(blueprint).stream().map(TemplateRegion::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Map<String, String> testValues(Map<String, String> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.size() > MAX_TEST_VALUES) {
            throw new IllegalArgumentException("At most " + MAX_TEST_VALUES + " values can be tested.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if (value != null && value.length() > MAX_TEST_VALUE_LENGTH) {
                throw new IllegalArgumentException("A test value is longer than " + MAX_TEST_VALUE_LENGTH
                        + " characters.");
            }
            out.put(key, value == null ? "" : value);
        });
        return out;
    }

    private static Map<String, Long> countBy(List<DocFieldRule> list, Function<DocFieldRule, String> key) {
        return list.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    private RulesDto toDto(DocBlueprint blueprint, List<DocFieldRule> list) {
        List<TemplateRegion> regions = currentRegions(blueprint);
        Map<String, Integer> regionOrder = new HashMap<>();
        for (int i = 0; i < regions.size(); i++) {
            regionOrder.putIfAbsent(regions.get(i).key(), i);
        }
        Map<Integer, String> templateNames = new HashMap<>();
        for (Integer id : list.stream().map(DocFieldRule::getSourceTemplateId).filter(Objects::nonNull)
                .collect(Collectors.toSet())) {
            templates.getAccessible(id, currentUser.requireUserId(), currentUser.requireBankId())
                    .ifPresent(t -> templateNames.put(id, t.getName()));
        }
        List<DocFieldRule> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.<DocFieldRule>comparingInt(r -> regionOrder.getOrDefault(r.getRegionKey(),
                        Integer.MAX_VALUE))
                .thenComparing(DocFieldRule::getRegionKey)
                .thenComparingInt(DocFieldRule::getSortOrder)
                .thenComparing(r -> r.getDocFieldRuleId() == null ? Long.MAX_VALUE : r.getDocFieldRuleId()));
        List<RulesDto.Rule> ruleDtos = sorted.stream()
                .map(r -> new RulesDto.Rule(r.getDocFieldRuleId(), r.getRegionKey(), r.getRuleType(),
                        FieldRuleChecks.readConfig(r.getConfigJson()), r.getMessage(), r.getSeverity(),
                        r.getOrigin(), r.getSourceTemplateId(),
                        r.getSourceTemplateId() == null ? null : templateNames.get(r.getSourceTemplateId()),
                        r.getSourceFieldName(), r.getSortOrder(), !regionOrder.containsKey(r.getRegionKey())))
                .toList();
        List<RulesDto.Field> fields = regions.stream()
                .map(r -> new RulesDto.Field(r.key(), r.label(), r.kind().name(), r.box().page()))
                .toList();
        return new RulesDto(blueprint.getRulesRevision(), ruleDtos, fields, ruleTypes(),
                inheritedViews(blueprint, regionOrder));
    }

    /**
     * ws34: the data template's rules re-keyed onto the mapped regions
     * (origin DATA_TEMPLATE, negative ids), in region order, with the data
     * template's name as {@code sourceTemplateName}.
     */
    private List<RulesDto.Rule> inheritedViews(DocBlueprint blueprint, Map<String, Integer> regionOrder) {
        if (effectiveRules == null) {
            return List.of();
        }
        EffectiveRules.Resolved resolved = effectiveRules.resolve(blueprint, List.of());
        List<DocFieldRule> sorted = new ArrayList<>(resolved.inherited());
        sorted.sort(Comparator.<DocFieldRule>comparingInt(r -> regionOrder.getOrDefault(r.getRegionKey(),
                        Integer.MAX_VALUE))
                .thenComparing(DocFieldRule::getRegionKey)
                .thenComparingInt(DocFieldRule::getSortOrder)
                .thenComparing(r -> r.getDocFieldRuleId() == null ? 0L : -r.getDocFieldRuleId()));
        return sorted.stream()
                .map(r -> new RulesDto.Rule(r.getDocFieldRuleId(), r.getRegionKey(), r.getRuleType(),
                        FieldRuleChecks.readConfig(r.getConfigJson()), r.getMessage(), r.getSeverity(),
                        r.getOrigin(), r.getSourceTemplateId(), resolved.dataTemplateName(),
                        r.getSourceFieldName(), r.getSortOrder(), !regionOrder.containsKey(r.getRegionKey())))
                .toList();
    }

    /** The {@code ruleTypes} metadata of {@link RulesDto}. */
    static List<RulesDto.RuleType> ruleTypes() {
        return RuleTypeCatalog.all().stream()
                .map(e -> new RulesDto.RuleType(e.type().name(),
                        e.kinds().stream().map(RegionKind::name).toList(), e.configKeys()))
                .toList();
    }
}
