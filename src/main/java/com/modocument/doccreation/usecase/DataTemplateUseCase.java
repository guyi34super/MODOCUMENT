package com.modocument.doccreation.usecase;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.doccreation.dto.AddFlowTemplatesRequest;
import com.modocument.doccreation.dto.CopyFlowTemplateRequest;
import com.modocument.doccreation.dto.CopyFlowTemplatesRequest;
import com.modocument.doccreation.dto.CopyPreviewDto;
import com.modocument.doccreation.dto.DataFieldInput;
import com.modocument.doccreation.dto.DataRuleInput;
import com.modocument.doccreation.dto.DataTemplateDto;
import com.modocument.doccreation.dto.DataTemplateSummaryDto;
import com.modocument.doccreation.dto.ImportPreviewDto;
import com.modocument.doccreation.dto.SaveDataFieldsRequest;
import com.modocument.doccreation.dto.SaveDataRulesRequest;
import com.modocument.doccreation.dto.SaveDataTemplateRequest;
import com.modocument.doccreation.dto.TestDataRulesRequest;
import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocDataField;
import com.modocument.doccreation.entity.DocDataFieldRule;
import com.modocument.doccreation.entity.DocDataTemplate;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.fields.FieldPropsCodec;
import com.modocument.doccreation.fields.FieldPropsRules;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.repo.DocBlueprintJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldRuleJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.doccreation.rules.DocRuleEvaluator;
import com.modocument.doccreation.rules.DocRuleType;
import com.modocument.doccreation.rules.RegionView;
import com.modocument.doccreation.rules.RuleTypeCatalog;
import com.modocument.doccreation.usecase.FlowRuleConverter.Converted;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.PiiKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data templates of document creation (docs/doccreation/data-templates-contract.md
 * §2): the reusable fields a document collects and their rules, made from
 * scratch or copied from the fields and rules of one or several flow
 * templates (each field remembers the flow template it came from).
 *
 * <p>Data templates are owner-scoped like PDF templates: another user's, or
 * another bank's, answers 404 (audited as a probe). Copying from a flow
 * template also needs {@code template:read} and {@code validation:read}
 * (checked here, 403) and read access to that template
 * ({@link TemplateOwnership#requireReadable}).
 *
 * <p>Every save of the fields or the rules names the revision it was based
 * on (409 when stale), raises the template's RulesRevision by one under a
 * row lock, and raises the RulesRevision of every PDF template that chose
 * it, so documents validated earlier see that the rules changed. Field and
 * rule checks reuse the region property checks ({@link FieldPropsRules}) and
 * the ws33 rule checks ({@link FieldRuleChecks}, keyed by field name, with
 * the rule's kind checked against the field's). Audit records counts only,
 * never configs or values.
 */
@Service
public class DataTemplateUseCase {

    static final int MAX_NAME = 200;
    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_FIELDS = 500;
    static final int MAX_LABEL = 200;
    static final int MAX_SOURCE_FIELD = 255;
    /** The most flow templates one copy may name. */
    static final int MAX_SOURCES = 50;
    /** The longest field name ({@code [a-z0-9_]{1,64}}). */
    static final int MAX_KEY = 64;
    /** The kinds a data field may have (V330): the region kinds without SIGNATURE. */
    static final List<String> KINDS = List.of("TEXT", "MULTILINE", "DATE", "NUMBER", "CHOICE", "CHECKBOX", "IMAGE");

    static final String CHANGE_CREATED = "CREATED";
    static final String CHANGE_DETAILS = "DETAILS";
    static final String CHANGE_FIELDS = "FIELDS";
    static final String CHANGE_RULES = "RULES";
    private static final String ENTITY = "DocDataTemplate";

    private final DocDataTemplateJpaRepository dataTemplates;
    private final DocDataFieldJpaRepository fields;
    private final DocDataFieldRuleJpaRepository rules;
    private final DocBlueprintJpaRepository blueprints;
    private final DocCreationOwnership ownership;
    private final TemplateOwnership templateOwnership;
    private final TemplateRepository templates;
    private final TemplateFieldsRepository templateFields;
    private final TemplateDataTypeRepository dataTypes;
    private final ValidationRuleRepository flowRules;
    private final AuditRecorder audit;
    private final ObjectProvider<DocRuleEvaluator> evaluator;

    @PersistenceContext
    private EntityManager entityManager;

    public DataTemplateUseCase(DocDataTemplateJpaRepository dataTemplates,
                               DocDataFieldJpaRepository fields,
                               DocDataFieldRuleJpaRepository rules,
                               DocBlueprintJpaRepository blueprints,
                               DocCreationOwnership ownership,
                               TemplateOwnership templateOwnership,
                               TemplateRepository templates,
                               TemplateFieldsRepository templateFields,
                               TemplateDataTypeRepository dataTypes,
                               ValidationRuleRepository flowRules,
                               AuditRecorder audit,
                               ObjectProvider<DocRuleEvaluator> evaluator) {
        this.dataTemplates = dataTemplates;
        this.fields = fields;
        this.rules = rules;
        this.blueprints = blueprints;
        this.ownership = ownership;
        this.templateOwnership = templateOwnership;
        this.templates = templates;
        this.templateFields = templateFields;
        this.dataTypes = dataTypes;
        this.flowRules = flowRules;
        this.audit = audit;
        this.evaluator = evaluator;
    }

    // ---------------------------------------------------------- templates

    /** {@code GET /data-templates}: the caller's data templates, by name. */
    @Transactional(readOnly = true)
    public List<DataTemplateSummaryDto> list() {
        int bankId = ownership.requireBankId();
        List<DocDataTemplate> own = dataTemplates.findByBankIdAndOwnerUserIdOrderByNameAscDocDataTemplateIdAsc(
                bankId, ownership.requireUserId());
        if (own.isEmpty()) {
            return List.of();
        }
        Set<Integer> ids = own.stream().map(DocDataTemplate::getDocDataTemplateId).collect(Collectors.toSet());
        Map<Integer, Long> fieldCounts = counts(fields.countByTemplate(bankId, ids));
        Map<Integer, Long> ruleCounts = counts(rules.countByTemplate(bankId, ids));
        Map<Integer, Long> usedBy = counts(blueprints.countByDataTemplate(bankId, ids));
        Map<Integer, String> names = templateNames(own.stream().map(DocDataTemplate::getSourceTemplateId).toList());
        return own.stream()
                .map(t -> new DataTemplateSummaryDto(t.getDocDataTemplateId(), t.getName(), t.getDescription(),
                        fieldCounts.getOrDefault(t.getDocDataTemplateId(), 0L).intValue(),
                        ruleCounts.getOrDefault(t.getDocDataTemplateId(), 0L).intValue(),
                        t.getSourceTemplateId(),
                        t.getSourceTemplateId() == null ? null : names.get(t.getSourceTemplateId()),
                        usedBy.getOrDefault(t.getDocDataTemplateId(), 0L).intValue(),
                        t.getUpdatedAt()))
                .toList();
    }

    /** {@code POST /data-templates}: an empty data template. */
    @Transactional
    public DataTemplateDto create(SaveDataTemplateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A name is required.");
        }
        DocDataTemplate template = newTemplate(name(request.name()), description(request.description()), null);
        audit.success(AuditActions.DOC_DATA_TEMPLATE_SAVED, ENTITY, String.valueOf(template.getDocDataTemplateId()),
                null, savedValues(CHANGE_CREATED, 0, 0, template.getRulesRevision()));
        return toDto(template);
    }

    /** {@code GET /data-templates/{id}}. */
    @Transactional(readOnly = true)
    public DataTemplateDto get(int id) {
        return toDto(require(id));
    }

    /** {@code PUT /data-templates/{id}}: the name and description. */
    @Transactional
    public DataTemplateDto update(int id, SaveDataTemplateRequest request) {
        DocDataTemplate template = lock(require(id));
        if (request == null) {
            throw new IllegalArgumentException("A name is required.");
        }
        template.setName(name(request.name()));
        template.setDescription(description(request.description()));
        template.setUpdatedAt(LocalDateTime.now());
        dataTemplates.save(template);
        audit.success(AuditActions.DOC_DATA_TEMPLATE_SAVED, ENTITY, String.valueOf(id), null,
                savedValues(CHANGE_DETAILS, fieldsOf(template).size(), rulesOf(template).size(),
                        template.getRulesRevision()));
        return toDto(template);
    }

    /**
     * {@code DELETE /data-templates/{id}}: the template with its fields and
     * rules.
     *
     * @throws IllegalStateException while a PDF template uses it (409)
     */
    @Transactional
    public void delete(int id) {
        DocDataTemplate template = lock(require(id));
        int used = usedBy(template).size();
        if (used > 0) {
            throw new IllegalStateException("This data template is used by " + used + " PDF template"
                    + (used == 1 ? "" : "s") + "; choose another data template for "
                    + (used == 1 ? "it" : "them") + " first.");
        }
        int fieldCount = fields.deleteTemplate(template.getBankId(), id);
        int ruleCount = rules.deleteTemplate(template.getBankId(), id);
        dataTemplates.delete(template);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fields", fieldCount);
        values.put("rules", ruleCount);
        audit.success(AuditActions.DOC_DATA_TEMPLATE_DELETED, ENTITY, String.valueOf(id), null, values);
    }

    // ------------------------------------------------------ fields, rules

    /**
     * {@code PUT /data-templates/{id}/fields}: replaces the field set. The
     * rules of a removed field are deleted, and so are the REQUIRED_IF and
     * COMPARE rules that name it.
     *
     * @throws IllegalStateException    when {@code baseRevision} is stale (409)
     * @throws IllegalArgumentException when a field fails a check, or a kept
     *                                  field's new kind does not fit one of its rules (400)
     */
    @Transactional
    public DataTemplateDto saveFields(int id, SaveDataFieldsRequest request) {
        DocDataTemplate template = lock(require(id));
        if (request == null || request.fields() == null) {
            throw new IllegalArgumentException("The fields are required.");
        }
        requireCurrentRevision(template, request.baseRevision());
        List<DocDataField> checked = checkFields(request.fields());
        Map<String, RegionKind> kinds = kindsOf(checked);

        List<DocDataFieldRule> removed = new ArrayList<>();
        for (DocDataFieldRule rule : rulesOf(template)) {
            String other = otherField(rule);
            if (!kinds.containsKey(rule.getFieldName()) || other != null && !kinds.containsKey(other)) {
                removed.add(rule);
                continue;
            }
            DocRuleType type = DocRuleType.parse(rule.getRuleType()).orElse(null);
            RegionKind kind = kinds.get(rule.getFieldName());
            if (type != null && !RuleTypeCatalog.fits(type, kind)) {
                throw new IllegalArgumentException("Field \"" + rule.getFieldName() + "\": its " + type.name()
                        + " rule does not apply to " + FieldRuleChecks.kindWords(kind)
                        + " field; remove the rule first or keep the kind.");
            }
        }
        // FieldInput has no sourceTemplateId: a kept field (same name, same flow field) keeps its flow template
        Map<String, DocDataField> before = new HashMap<>();
        fieldsOf(template).forEach(f -> before.putIfAbsent(f.getFieldName(), f));
        rules.deleteAll(removed);
        fields.deleteTemplate(template.getBankId(), id);
        for (DocDataField field : checked) {
            field.setBankId(template.getBankId());
            field.setDataTemplateId(id);
            DocDataField kept = before.get(field.getFieldName());
            if (kept != null && field.getSourceFieldName() != null
                    && field.getSourceFieldName().equals(kept.getSourceFieldName())) {
                field.setSourceTemplateId(kept.getSourceTemplateId());
            }
        }
        fields.saveAll(checked);
        bump(template);

        int ruleCount = rulesOf(template).size();
        audit.success(AuditActions.DOC_DATA_TEMPLATE_SAVED, ENTITY, String.valueOf(id), null,
                savedValues(CHANGE_FIELDS, checked.size(), ruleCount, template.getRulesRevision()));
        return toDto(template);
    }

    /**
     * {@code PUT /data-templates/{id}/rules}: replaces the rule set.
     *
     * @throws IllegalStateException    when {@code baseRevision} is stale (409)
     * @throws IllegalArgumentException when a rule fails a check (400)
     */
    @Transactional
    public DataTemplateDto saveRules(int id, SaveDataRulesRequest request) {
        DocDataTemplate template = lock(require(id));
        if (request == null) {
            throw new IllegalArgumentException("The rules are required.");
        }
        requireCurrentRevision(template, request.baseRevision());
        List<DocDataField> current = fieldsOf(template);
        List<DocDataFieldRule> checked = checkRules(request.rules(), template, current);
        rules.deleteTemplate(template.getBankId(), id);
        List<DocDataFieldRule> saved = rules.saveAll(checked);
        bump(template);
        audit.success(AuditActions.DOC_DATA_TEMPLATE_SAVED, ENTITY, String.valueOf(id), null,
                savedValues(CHANGE_RULES, current.size(), saved.size(), template.getRulesRevision()));
        return toDto(template);
    }

    /**
     * {@code POST /data-templates/{id}/rules/test}: checks sample values (by
     * field name) against the given (unsaved) rules or the saved ones, with
     * the evaluator of document validation. An IMAGE field with a non-blank
     * sample value counts as holding a photo. Nothing is stored.
     */
    @Transactional(readOnly = true)
    public ValidationResultDto test(int id, TestDataRulesRequest request) {
        DocDataTemplate template = require(id);
        Map<String, String> values = DocFieldRuleUseCase.testValues(request == null ? null : request.values());
        List<DocDataField> current = fieldsOf(template);
        List<DocDataFieldRule> dataRules = request != null && request.rules() != null
                ? checkRules(request.rules(), template, current)
                : rulesOf(template);
        DocRuleEvaluator checker = evaluator.getIfAvailable();
        if (checker == null) {
            throw new DocCreationException(503, "RULES_UNAVAILABLE",
                    "Field rules cannot be tested right now; try again later.");
        }
        List<RegionView> regions = current.stream()
                .map(f -> new RegionView(f.getFieldName(), f.getLabel(), RegionKind.valueOf(f.getKind()),
                        FieldPropsCodec.read(f.getPropsJson())))
                .toList();
        Set<String> placed = current.stream()
                .filter(f -> RegionKind.IMAGE.name().equals(f.getKind()))
                .map(DocDataField::getFieldName)
                .filter(name -> values.get(name) != null && !values.get(name).isBlank())
                .collect(Collectors.toSet());
        List<DocFieldRule> asFieldRules = dataRules.stream().map(DataTemplateUseCase::asFieldRule).toList();
        return checker.evaluate(regions, values, placed, asFieldRules, template.getRulesRevision());
    }

    // ------------------------------------------------- copy from the flow

    /** {@code GET /data-templates/copy-preview?templateId=}: what a copy of the flow template would create. */
    @Transactional(readOnly = true)
    public CopyPreviewDto copyPreview(Integer templateId) {
        requireCopyAccess();
        Template template = flowTemplate(templateId);
        List<CopyPreviewDto.Field> out = new ArrayList<>();
        for (PlannedField field : plan(template, null)) {
            out.add(new CopyPreviewDto.Field(field.flowName(), field.dataType(), field.kind().name(),
                    field.piiKind(),
                    field.rules().stream().map(r -> new ImportPreviewDto.PreviewRule(r.type().name(), r.config(),
                            r.message())).toList(),
                    field.skipped()));
        }
        return new CopyPreviewDto(template.getTemplateId(), template.getName(), out);
    }

    /**
     * {@code POST /data-templates/from-flow-template}: a new data template
     * with the chosen flow fields (slugged, unique names; kind from the data
     * type; PII kind kept) and their convertible rules (IMPORTED, with the
     * source template and field). The one-template case of
     * {@link #copyFromFlowTemplates}; an empty field list makes an empty
     * template, as it always did.
     */
    @Transactional
    public DataTemplateDto copyFromFlowTemplate(CopyFlowTemplateRequest request) {
        requireCopyAccess();
        if (request == null) {
            throw new IllegalArgumentException("A flow template is required.");
        }
        Template template = flowTemplate(request.templateId());
        String name = name(request.name());
        Merged merged = merge(List.of(new Source(template, request.fields())), List.of(), List.of(), true);
        DocDataTemplate created = newTemplate(name, null, template.getTemplateId());
        store(created, merged);
        auditCopied(created, merged, false, template.getTemplateId());
        return toDto(created);
    }

    /**
     * {@code POST /data-templates/from-flow-templates}: a new data template
     * from the chosen fields of several flow templates (contract §2,
     * "Building from several flow templates"). Each field is copied as by
     * {@link #copyFromFlowTemplate} and remembers its flow template; a name
     * an earlier source already took gets the suffix {@code _<slug of the
     * flow template's name>}. The template's own SourceTemplateId is the flow
     * template that gave the most fields (the first on a tie).
     *
     * @throws IllegalArgumentException for no sources, a source with an empty
     *                                  field list, or an unknown flow field (400)
     */
    @Transactional
    public DataTemplateDto copyFromFlowTemplates(CopyFlowTemplatesRequest request) {
        requireCopyAccess();
        if (request == null) {
            throw new IllegalArgumentException("Choose at least one flow template.");
        }
        checkSources(request.sources());
        String name = name(request.name());
        List<Source> sources = readSources(request.sources());
        Merged merged = merge(sources, List.of(), List.of(), false);
        DocDataTemplate created = newTemplate(name, null, merged.mainTemplateId());
        store(created, merged);
        auditCopied(created, merged, false, null);
        return toDto(created);
    }

    /**
     * {@code POST /data-templates/{id}/add-from-flow-templates}: adds the
     * chosen flow fields and their rules after the template's own fields
     * (names that clash with its fields get the flow template's suffix).
     * Raises the revision, and that of every PDF template using it, like any
     * other save; the template's SourceTemplateId is set only when it had
     * none.
     *
     * @throws IllegalStateException    when {@code baseRevision} is stale (409)
     * @throws IllegalArgumentException as {@link #copyFromFlowTemplates} (400)
     */
    @Transactional
    public DataTemplateDto addFromFlowTemplates(int id, AddFlowTemplatesRequest request) {
        requireCopyAccess();
        DocDataTemplate template = lock(require(id));
        if (request == null) {
            throw new IllegalArgumentException("Choose at least one flow template.");
        }
        requireCurrentRevision(template, request.baseRevision());
        checkSources(request.sources());
        List<Source> sources = readSources(request.sources());
        Merged merged = merge(sources, fieldsOf(template), rulesOf(template), false);
        store(template, merged);
        if (template.getSourceTemplateId() == null) {
            template.setSourceTemplateId(merged.mainTemplateId());
        }
        bump(template);
        auditCopied(template, merged, true, null);
        return toDto(template);
    }

    /** A readable flow template and the flow field names wanted from it (null = all). */
    private record Source(Template template, List<String> wanted) {
    }

    /**
     * What a copy adds: the fields and rules (not yet stored: no bank or
     * template), the skipped rule count, and how many fields each flow
     * template gave, in the request's order.
     */
    private record Merged(List<DocDataField> fields, List<DocDataFieldRule> rules, int skipped,
                          Map<Integer, Integer> fieldsPerTemplate) {

        /** The flow template that gave the most fields; the first on a tie, null when there is none. */
        Integer mainTemplateId() {
            Integer main = null;
            int most = -1;
            for (Map.Entry<Integer, Integer> entry : fieldsPerTemplate.entrySet()) {
                if (entry.getValue() > most) {
                    main = entry.getKey();
                    most = entry.getValue();
                }
            }
            return main;
        }
    }

    /** The request's shape: at least one source, each naming a template once, no empty field list (400). */
    private static void checkSources(List<CopyFlowTemplatesRequest.Source> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one flow template.");
        }
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("Choose at most " + MAX_SOURCES + " flow templates.");
        }
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            CopyFlowTemplatesRequest.Source source = sources.get(i);
            String where = "Flow template " + (i + 1) + ": ";
            if (source == null || source.templateId() == null) {
                throw new IllegalArgumentException(where + "a flow template is required.");
            }
            if (!seen.add(source.templateId())) {
                throw new IllegalArgumentException(where + "this flow template is listed twice.");
            }
            if (source.fields() != null && source.fields().isEmpty()) {
                throw new IllegalArgumentException(where + "choose at least one field, or leave the list out "
                        + "to copy every field.");
            }
        }
    }

    /** Every source's flow template, read-checked ({@link TemplateOwnership#requireReadable}, 404). */
    private List<Source> readSources(List<CopyFlowTemplatesRequest.Source> sources) {
        List<Source> out = new ArrayList<>();
        for (CopyFlowTemplatesRequest.Source source : sources) {
            out.add(new Source(flowTemplate(source.templateId()), source.fields()));
        }
        return out;
    }

    /**
     * The fields and rules the {@code sources} add next to the template's
     * {@code existing} fields and rules (none for a new template), in the
     * order of the sources and, within one, of the flow template. A name
     * that is taken gets {@link #clashName} (the flow template suffix, then
     * a number); the single copy ({@code singleCopy}) keeps its _2, _3, ...
     *
     * @throws IllegalArgumentException for an unknown flow field or a limit (400)
     */
    private Merged merge(List<Source> sources, List<DocDataField> existing, List<DocDataFieldRule> existingRules,
                         boolean singleCopy) {
        Set<String> used = new HashSet<>();
        existing.forEach(f -> used.add(f.getFieldName()));
        int nextSort = existing.stream().mapToInt(DocDataField::getSortOrder).max().orElse(-1) + 1;
        List<DocDataField> newFields = new ArrayList<>();
        List<DocDataFieldRule> newRules = new ArrayList<>();
        Map<Integer, Integer> perTemplate = new LinkedHashMap<>();
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Source source : sources) {
            Template template = source.template();
            List<PlannedField> planned = plan(template, source.wanted());
            String templateSlug = templateSlug(template);
            Map<String, Integer> perField = new HashMap<>();
            for (PlannedField field : planned) {
                String base = OverlayMapper.slug(field.flowName());
                String fieldName = singleCopy ? uniqueName(base, used) : clashName(base, templateSlug, used);
                String flowName = FieldRuleChecks.truncate(field.flowName(), MAX_SOURCE_FIELD);
                DocDataField row = new DocDataField();
                row.setFieldName(fieldName);
                row.setLabel(FieldRuleChecks.truncate(field.flowName(), MAX_LABEL));
                row.setKind(field.kind().name());
                row.setPiiKind(field.piiKind());
                row.setSourceFieldName(flowName);
                row.setSourceTemplateId(template.getTemplateId());
                row.setSortOrder(nextSort++);
                newFields.add(row);
                skipped += field.skipped().size();
                for (Converted converted : field.rules()) {
                    DocDataFieldRule rule = new DocDataFieldRule();
                    rule.setFieldName(fieldName);
                    rule.setRuleType(converted.type().name());
                    rule.setConfigJson(converted.config().toString());
                    rule.setMessage(converted.message());
                    rule.setSeverity("ERROR");
                    rule.setOrigin(DocFieldRuleUseCase.ORIGIN_IMPORTED);
                    rule.setSourceTemplateId(template.getTemplateId());
                    rule.setSourceFieldName(flowName);
                    rule.setSortOrder(perField.merge(fieldName, 1, Integer::sum) - 1);
                    rule.setUpdatedAt(now);
                    newRules.add(rule);
                }
            }
            perTemplate.put(template.getTemplateId(), planned.size());
        }
        if (existing.size() + newFields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("A data template has at most " + MAX_FIELDS + " fields.");
        }
        List<String> ruleKeys = new ArrayList<>();
        existingRules.forEach(r -> ruleKeys.add(r.getFieldName()));
        newRules.forEach(r -> ruleKeys.add(r.getFieldName()));
        FieldRuleChecks.checkLimits(ruleKeys, "A data template");
        return new Merged(newFields, newRules, skipped, perTemplate);
    }

    /** Stores the merged fields and rules into {@code target}. */
    private void store(DocDataTemplate target, Merged merged) {
        for (DocDataField field : merged.fields()) {
            field.setBankId(target.getBankId());
            field.setDataTemplateId(target.getDocDataTemplateId());
        }
        for (DocDataFieldRule rule : merged.rules()) {
            rule.setBankId(target.getBankId());
            rule.setDataTemplateId(target.getDocDataTemplateId());
        }
        fields.saveAll(merged.fields());
        rules.saveAll(merged.rules());
    }

    /**
     * {@code DOC_DATA_TEMPLATE_COPIED}: the flow template ids and the counts,
     * never names, configs or values. The single copy also keeps its
     * {@code templateId}.
     */
    private void auditCopied(DocDataTemplate target, Merged merged, boolean added, Integer singleTemplateId) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (singleTemplateId != null) {
            values.put("templateId", singleTemplateId);
        }
        values.put("templates", List.copyOf(merged.fieldsPerTemplate().keySet()));
        values.put("fields", merged.fields().size());
        values.put("rules", merged.rules().size());
        values.put("skipped", merged.skipped());
        values.put("added", added);
        audit.success(AuditActions.DOC_DATA_TEMPLATE_COPIED, ENTITY, String.valueOf(target.getDocDataTemplateId()),
                null, values);
    }

    /** The slug of the flow template's name ("template_<id>" when it has none). */
    private static String templateSlug(Template template) {
        String slug = OverlayMapper.slug(template.getName() == null ? "" : template.getName());
        return slug.isEmpty() ? "template_" + template.getTemplateId() : slug;
    }

    /**
     * The name of a field copied from several flow templates (contract §2):
     * {@code base} ("field" when empty) when it is free; otherwise
     * {@code base_<templateSlug>}, then {@code base_<templateSlug>_2},
     * {@code _3}, ... (e.g. email, email_accounts, email_accounts_2). The
     * base is cut so the whole name keeps its suffix within 64 characters.
     * The name is added to {@code used}.
     */
    static String clashName(String base, String templateSlug, Set<String> used) {
        String root = base == null || base.isEmpty() ? "field" : base;
        if (used.add(root)) {
            return root;
        }
        for (int n = 1; ; n++) {
            String suffix = "_" + templateSlug + (n == 1 ? "" : "_" + n);
            String name = root.substring(0, Math.max(1, Math.min(root.length(), MAX_KEY - suffix.length())))
                    + suffix;
            if (used.add(name)) {
                return name;
            }
        }
    }

    /** One flow field to copy: its kind, PII kind, the rules it brings and the ones skipped. */
    record PlannedField(String flowName, String dataType, RegionKind kind, String piiKind, List<Converted> rules,
                        List<ImportPreviewDto.Skipped> skipped) {
    }

    /** The flow fields to copy ({@code wanted} = flow field names, null = all), in the flow template's order. */
    private List<PlannedField> plan(Template template, List<String> wanted) {
        Map<Integer, String> typeNames = new HashMap<>();
        for (TemplateDataType type : dataTypes.list()) {
            typeNames.put(type.getTemplateDataTypeId(), type.getDataType());
        }
        List<TemplateFields> flowFields = templateFields.listByTemplateId(template.getTemplateId()).stream()
                .sorted(Comparator.comparingInt(TemplateFields::getTemplateFieldsId))
                .toList();
        Set<String> chosen = null;
        if (wanted != null) {
            Set<String> names = flowFields.stream().map(TemplateFields::getFieldName).collect(Collectors.toSet());
            chosen = new LinkedHashSet<>();
            for (String name : wanted) {
                String clean = name == null ? "" : name.strip();
                if (!names.contains(clean)) {
                    throw new IllegalArgumentException("Field \"" + FieldRuleChecks.truncate(clean, 80)
                            + "\" is not in the flow template.");
                }
                chosen.add(clean);
            }
        }
        Map<String, List<ValidationRule>> byField = flowRulesByField(template.getTemplateId());
        List<PlannedField> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TemplateFields field : flowFields) {
            String flowName = field.getFieldName();
            if (flowName == null || chosen != null && !chosen.contains(flowName) || !seen.add(flowName)) {
                continue;
            }
            String dataType = typeNames.getOrDefault(field.getTemplateDataTypeId(),
                    defaultTypeName(field.getTemplateDataTypeId()));
            RegionKind kind = kindOf(dataType);
            List<Converted> converted = new ArrayList<>();
            List<ImportPreviewDto.Skipped> skipped = new ArrayList<>();
            for (ValidationRule flowRule : byField.getOrDefault(flowName, List.of())) {
                String flowType = flowRule.getRuleType() == null ? "UNKNOWN" : flowRule.getRuleType().name();
                Converted rule;
                try {
                    rule = FlowRuleConverter.convert(flowRule);
                } catch (IllegalArgumentException e) {
                    skipped.add(new ImportPreviewDto.Skipped(flowType, e.getMessage()));
                    continue;
                }
                if (!RuleTypeCatalog.fits(rule.type(), kind)) {
                    skipped.add(new ImportPreviewDto.Skipped(flowType, rule.type().name() + " does not apply to "
                            + FieldRuleChecks.kindWords(kind) + " field"));
                    continue;
                }
                converted.add(rule);
            }
            out.add(new PlannedField(flowName, dataType, kind,
                    field.getPiiKind() == null ? null : field.getPiiKind().name(), converted, skipped));
        }
        return out;
    }

    /** Number → NUMBER, Date → DATE, anything else TEXT (data-templates-contract §2). */
    static RegionKind kindOf(String dataType) {
        if ("Number".equalsIgnoreCase(dataType)) {
            return RegionKind.NUMBER;
        }
        if ("Date".equalsIgnoreCase(dataType)) {
            return RegionKind.DATE;
        }
        return RegionKind.TEXT;
    }

    /** The seeded TemplateDataTypes (V2): 1 Text, 2 Number, 3 Date. */
    private static String defaultTypeName(int id) {
        return switch (id) {
            case 2 -> "Number";
            case 3 -> "Date";
            default -> "Text";
        };
    }

    /** {@code base} (or "field"), made unique among {@code used} with _2, _3, ... */
    static String uniqueName(String base, Set<String> used) {
        String root = base == null || base.isEmpty() ? "field" : base;
        String name = root;
        for (int n = 2; used.contains(name); n++) {
            name = root + "_" + n;
        }
        used.add(name);
        return name;
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

    private Template flowTemplate(Integer templateId) {
        if (templateId == null) {
            throw new IllegalArgumentException("A flow template is required.");
        }
        return templateOwnership.requireReadable(templateId);
    }

    private void requireCopyAccess() {
        if (!ownership.callerHas(Permission.TEMPLATE_READ) || !ownership.callerHas(Permission.VALIDATION_READ)) {
            throw new AccessDeniedException("Copying a flow template needs template:read and validation:read.");
        }
    }

    // ------------------------------------------------------------- checks

    /** The checked fields (not yet stored: no id, bank or template), sort order = list position. */
    static List<DocDataField> checkFields(List<DataFieldInput> inputs) {
        if (inputs.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("A data template has at most " + MAX_FIELDS + " fields.");
        }
        List<DocDataField> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (DataFieldInput input : inputs) {
            if (input == null) {
                throw new IllegalArgumentException("Field " + (out.size() + 1) + " is empty.");
            }
            String name = input.name() == null ? "" : input.name().strip();
            if (!OverlayMapper.KEY.matcher(name).matches()) {
                throw new IllegalArgumentException("Field name \"" + FieldRuleChecks.truncate(name, 80)
                        + "\" is invalid: use 1-64 lower-case letters, digits or underscores.");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Two fields share the name \"" + name + "\".");
            }
            String prefix = "Field \"" + name + "\": ";
            String label = input.label() == null ? "" : input.label().strip();
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                throw new IllegalArgumentException(prefix + "the label must have 1 to " + MAX_LABEL
                        + " characters.");
            }
            String kindName = input.kind() == null ? "" : input.kind().strip().toUpperCase(Locale.ROOT);
            if (!KINDS.contains(kindName)) {
                throw new IllegalArgumentException(prefix + "the kind must be one of " + String.join(", ", KINDS)
                        + ".");
            }
            RegionKind kind = RegionKind.valueOf(kindName);
            String piiKind = null;
            if (input.piiKind() != null && !input.piiKind().isBlank()) {
                piiKind = input.piiKind().strip().toUpperCase(Locale.ROOT);
                try {
                    PiiKind.valueOf(piiKind);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(prefix + "unknown PII kind \""
                            + FieldRuleChecks.truncate(input.piiKind().strip(), 40) + "\".");
                }
            }
            FieldProps props = FieldPropsRules.check(name, kind, input.props());
            String sourceField = input.sourceFieldName() == null || input.sourceFieldName().isBlank() ? null
                    : input.sourceFieldName().strip();
            if (sourceField != null && sourceField.length() > MAX_SOURCE_FIELD) {
                throw new IllegalArgumentException(prefix + "the source field name is too long.");
            }
            DocDataField field = new DocDataField();
            field.setFieldName(name);
            field.setLabel(label);
            field.setKind(kindName);
            field.setPiiKind(piiKind);
            field.setPropsJson(FieldPropsCodec.write(props));
            field.setSourceFieldName(sourceField);
            field.setSortOrder(out.size());
            out.add(field);
        }
        return out;
    }

    /** The checked rules of {@code template} (not yet stored: no id), against its {@code current} fields. */
    private static List<DocDataFieldRule> checkRules(List<DataRuleInput> inputs, DocDataTemplate template,
                                                     List<DocDataField> current) {
        List<FieldRuleChecks.Input> list = inputs == null ? null : inputs.stream()
                .map(r -> r == null ? null : new FieldRuleChecks.Input(r.fieldName(), r.ruleType(), r.config(),
                        r.message(), r.severity(), r.origin(), r.sourceTemplateId(), r.sourceFieldName()))
                .toList();
        LocalDateTime now = LocalDateTime.now();
        List<DocDataFieldRule> out = new ArrayList<>();
        for (FieldRuleChecks.Checked checked : FieldRuleChecks.checkAll(list,
                FieldRuleChecks.Scope.dataTemplate(kindsOf(current)))) {
            DocDataFieldRule rule = new DocDataFieldRule();
            rule.setBankId(template.getBankId());
            rule.setDataTemplateId(template.getDocDataTemplateId());
            rule.setFieldName(checked.key());
            rule.setRuleType(checked.ruleType());
            rule.setConfigJson(checked.configJson());
            rule.setMessage(checked.message());
            rule.setSeverity(checked.severity());
            rule.setOrigin(checked.origin());
            rule.setSourceTemplateId(checked.sourceTemplateId());
            rule.setSourceFieldName(checked.sourceFieldName());
            rule.setSortOrder(checked.sortOrder());
            rule.setUpdatedAt(now);
            out.add(rule);
        }
        return out;
    }

    private static Map<String, RegionKind> kindsOf(List<DocDataField> list) {
        Map<String, RegionKind> kinds = new LinkedHashMap<>();
        for (DocDataField field : list) {
            kinds.put(field.getFieldName(), RegionKind.valueOf(field.getKind()));
        }
        return kinds;
    }

    /** The other field a REQUIRED_IF or COMPARE rule names, else null. */
    private static String otherField(DocDataFieldRule rule) {
        if (!DocRuleType.REQUIRED_IF.name().equals(rule.getRuleType())
                && !DocRuleType.COMPARE.name().equals(rule.getRuleType())) {
            return null;
        }
        var node = FieldRuleChecks.readConfig(rule.getConfigJson()).get("Field");
        return node == null || !node.isValueNode() || node.isNull() ? null : node.asText();
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A name is required.");
        }
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("The name is limited to " + MAX_NAME + " characters.");
        }
        return name;
    }

    private static String description(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String description = raw.strip();
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("The description is limited to " + MAX_DESCRIPTION
                    + " characters.");
        }
        return description;
    }

    private static void requireCurrentRevision(DocDataTemplate template, Integer baseRevision) {
        if (baseRevision != null && baseRevision != template.getRulesRevision()) {
            throw new IllegalStateException("The data template was changed meanwhile (it is now at revision "
                    + template.getRulesRevision() + "). Reload it and make your changes again.");
        }
    }

    // ------------------------------------------------------------ helpers

    /**
     * The caller's data template in the caller's bank.
     *
     * @throws NoSuchElementException otherwise (404; another user's or bank's
     *                                cannot be told apart from a missing one)
     */
    public DocDataTemplate require(int id) {
        return dataTemplates.findByDocDataTemplateIdAndBankIdAndOwnerUserId(id, ownership.requireBankId(),
                ownership.requireUserId()).orElseThrow(() -> {
                    audit.record(AuditActions.notFoundProbe(ENTITY, id));
                    return new NoSuchElementException("Data template " + id + " not found.");
                });
    }

    /** Locks the template's row for this transaction, so saves of one template run one after the other. */
    private DocDataTemplate lock(DocDataTemplate template) {
        if (entityManager != null && entityManager.contains(template)) {
            entityManager.refresh(template, LockModeType.PESSIMISTIC_WRITE);
        }
        return template;
    }

    /** RulesRevision + 1 on the template and on every PDF template that chose it. */
    private void bump(DocDataTemplate template) {
        template.setRulesRevision(template.getRulesRevision() + 1);
        template.setUpdatedAt(LocalDateTime.now());
        dataTemplates.save(template);
        blueprints.bumpRulesRevisionOfDataTemplate(template.getBankId(), template.getDocDataTemplateId());
    }

    private DocDataTemplate newTemplate(String name, String description, Integer sourceTemplateId) {
        LocalDateTime now = LocalDateTime.now();
        DocDataTemplate template = new DocDataTemplate();
        template.setBankId(ownership.requireBankId());
        template.setOwnerUserId(ownership.requireUserId());
        template.setName(name);
        template.setDescription(description);
        template.setSourceTemplateId(sourceTemplateId);
        template.setRulesRevision(0);
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        return dataTemplates.saveAndFlush(template);
    }

    private static Map<String, Object> savedValues(String change, int fieldCount, int ruleCount, int revision) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("change", change);
        values.put("fields", fieldCount);
        values.put("rules", ruleCount);
        values.put("revision", revision);
        return values;
    }

    private List<DocDataField> fieldsOf(DocDataTemplate template) {
        return fields.findByBankIdAndDataTemplateIdOrderBySortOrderAscDocDataFieldIdAsc(template.getBankId(),
                template.getDocDataTemplateId());
    }

    private List<DocDataFieldRule> rulesOf(DocDataTemplate template) {
        return rules.findByBankIdAndDataTemplateIdOrderByFieldNameAscSortOrderAscDocDataFieldRuleIdAsc(
                template.getBankId(), template.getDocDataTemplateId());
    }

    private List<DocBlueprint> usedBy(DocDataTemplate template) {
        return blueprints.findByBankIdAndDataTemplateIdOrderByDocBlueprintIdAsc(template.getBankId(),
                template.getDocDataTemplateId());
    }

    /** A data rule as a (transient) region rule keyed by the field name, for the evaluator. */
    private static DocFieldRule asFieldRule(DocDataFieldRule data) {
        DocFieldRule rule = new DocFieldRule();
        rule.setDocFieldRuleId(data.getDocDataFieldRuleId());
        rule.setBankId(data.getBankId());
        rule.setRegionKey(data.getFieldName());
        rule.setRuleType(data.getRuleType());
        rule.setConfigJson(data.getConfigJson());
        rule.setMessage(data.getMessage());
        rule.setSeverity(data.getSeverity());
        rule.setOrigin(data.getOrigin());
        rule.setSourceTemplateId(data.getSourceTemplateId());
        rule.setSourceFieldName(data.getSourceFieldName());
        rule.setSortOrder(data.getSortOrder());
        return rule;
    }

    private static Map<Integer, Long> counts(List<Object[]> rows) {
        Map<Integer, Long> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return out;
    }

    /** The names of the flow templates the caller can read, by id, in one lookup. */
    private Map<Integer, String> templateNames(List<Integer> ids) {
        Set<Integer> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> names = new HashMap<>();
        for (Template t : templates.listAccessible(wanted, ownership.requireUserId(), ownership.requireBankId())) {
            names.put(t.getTemplateId(), t.getName());
        }
        return names;
    }

    private DataTemplateDto toDto(DocDataTemplate template) {
        List<DocDataField> fieldRows = fieldsOf(template);
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < fieldRows.size(); i++) {
            order.putIfAbsent(fieldRows.get(i).getFieldName(), i);
        }
        List<DocDataFieldRule> ruleRows = new ArrayList<>(rulesOf(template));
        ruleRows.sort(Comparator.<DocDataFieldRule>comparingInt(r -> order.getOrDefault(r.getFieldName(),
                        Integer.MAX_VALUE))
                .thenComparing(DocDataFieldRule::getFieldName)
                .thenComparingInt(DocDataFieldRule::getSortOrder)
                .thenComparing(r -> r.getDocDataFieldRuleId() == null ? Long.MAX_VALUE : r.getDocDataFieldRuleId()));
        List<Integer> sourceIds = new ArrayList<>();
        sourceIds.add(template.getSourceTemplateId());
        fieldRows.forEach(f -> sourceIds.add(f.getSourceTemplateId()));
        ruleRows.forEach(r -> sourceIds.add(r.getSourceTemplateId()));
        Map<Integer, String> names = templateNames(sourceIds);

        List<DataTemplateDto.Field> fieldDtos = fieldRows.stream()
                .map(f -> {
                    FieldProps props = FieldPropsCodec.read(f.getPropsJson());
                    return new DataTemplateDto.Field(f.getFieldName(), f.getLabel(), f.getKind(), f.getPiiKind(),
                            props.isEmpty() ? null : props, f.getSourceFieldName(), f.getSortOrder(),
                            f.getSourceTemplateId(),
                            f.getSourceTemplateId() == null ? null : names.get(f.getSourceTemplateId()));
                })
                .toList();
        List<DataTemplateDto.Rule> ruleDtos = ruleRows.stream()
                .map(r -> new DataTemplateDto.Rule(r.getDocDataFieldRuleId(), r.getFieldName(), r.getRuleType(),
                        FieldRuleChecks.readConfig(r.getConfigJson()), r.getMessage(), r.getSeverity(),
                        r.getOrigin(), r.getSourceTemplateId(),
                        r.getSourceTemplateId() == null ? null : names.get(r.getSourceTemplateId()),
                        r.getSourceFieldName(), r.getSortOrder()))
                .toList();
        List<DataTemplateDto.UsedBy> usedBy = usedBy(template).stream()
                .map(b -> new DataTemplateDto.UsedBy(b.getDocBlueprintId(), b.getName()))
                .toList();
        return new DataTemplateDto(template.getDocDataTemplateId(), template.getName(), template.getDescription(),
                template.getRulesRevision(), template.getSourceTemplateId(),
                template.getSourceTemplateId() == null ? null : names.get(template.getSourceTemplateId()),
                fieldDtos, ruleDtos, usedBy, DocFieldRuleUseCase.ruleTypes());
    }
}
