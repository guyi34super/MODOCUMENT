package com.modocument.doccreation.usecase;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.doccreation.dto.ChooseDataTemplateRequest;
import com.modocument.doccreation.dto.DataMappingDto;
import com.modocument.doccreation.dto.RegionDto;
import com.modocument.doccreation.dto.SaveDataMappingRequest;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSource;
import com.modocument.doccreation.entity.DocDataField;
import com.modocument.doccreation.entity.DocDataTemplate;
import com.modocument.doccreation.entity.DocTemplateRegion;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.repo.DocBlueprintRepository;
import com.modocument.doccreation.repo.DocBlueprintSourceJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.doccreation.repo.DocTemplateRegionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A PDF template's data template and field mapping
 * (docs/doccreation/data-templates-contract.md §3): choose (or clear) the
 * data template — the mapping is then pre-filled from the suggestions —
 * and save the mapping (region key to data field name).
 *
 * <p>The caller must own the PDF template ({@link DocCreationOwnership}) and
 * the data template (owner-scoped, same bank; otherwise 404). Every change
 * locks the template row, raises its RulesRevision by one (documents
 * validated before see that the rules changed), keeps the managed PRIMARY
 * data source in step and is audited DOC_BLUEPRINT_DATA_MAPPING_SAVED with
 * ids and counts only.
 *
 * <p>MANAGED SOURCE — while the data template was copied from a flow
 * template ({@code SourceTemplateId}) the caller can read, the template
 * keeps one PRIMARY {@link DocBlueprintSource} with {@code managed=true} for
 * it, mapping each region to the flow field ({@code sourceFieldName}) of the
 * data field mapped to it. It is created when there is no PRIMARY, updated
 * when the PRIMARY is managed, and deleted when the data template changes to
 * one without a (readable) source or the choice is cleared. A PRIMARY made
 * by hand is never touched.
 */
@Service
public class BlueprintDataMappingUseCase {

    public static final String CHANGE_CHOSEN = "CHOSEN";
    public static final String CHANGE_CLEARED = "CLEARED";
    public static final String CHANGE_MAPPING = "MAPPING";

    static final String SOURCE_CREATED = "CREATED";
    static final String SOURCE_UPDATED = "UPDATED";
    static final String SOURCE_DELETED = "DELETED";
    static final String SOURCE_UNCHANGED = "UNCHANGED";
    static final String SOURCE_HAND_MADE = "HAND_MADE";
    static final String SOURCE_NONE = "NONE";

    private final DocCreationOwnership ownership;
    private final DocBlueprintRepository blueprints;
    private final TemplateVersions versions;
    private final DocDataTemplateJpaRepository dataTemplates;
    private final DocDataFieldJpaRepository dataFields;
    private final DocBlueprintSourceJpaRepository sources;
    private final DocTemplateRegionRepository regionRows;
    private final BlueprintMappingSuggester suggester;
    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;

    @PersistenceContext
    private EntityManager entityManager;

    public BlueprintDataMappingUseCase(DocCreationOwnership ownership,
                                       DocBlueprintRepository blueprints,
                                       TemplateVersions versions,
                                       DocDataTemplateJpaRepository dataTemplates,
                                       DocDataFieldJpaRepository dataFields,
                                       DocBlueprintSourceJpaRepository sources,
                                       DocTemplateRegionRepository regionRows,
                                       BlueprintMappingSuggester suggester,
                                       TemplateRepository templates,
                                       CurrentUserProvider currentUser,
                                       AuditRecorder audit) {
        this.ownership = ownership;
        this.blueprints = blueprints;
        this.versions = versions;
        this.dataTemplates = dataTemplates;
        this.dataFields = dataFields;
        this.sources = sources;
        this.regionRows = regionRows;
        this.suggester = suggester;
        this.templates = templates;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- API

    /** {@code GET /blueprints/{id}/data-mapping}. */
    @Transactional(readOnly = true)
    public DataMappingDto get(int blueprintId) {
        return toDto(ownership.requireTemplate(blueprintId));
    }

    /**
     * {@code PUT /blueprints/{id}/data-template}: chooses a data template
     * (the mapping is pre-filled from the suggestions: same name, or a score
     * at the threshold, one-to-one, kind-compatible) or clears the choice
     * and the mapping. Choosing the data template already chosen changes
     * nothing (the mapping is kept).
     *
     * @throws NoSuchElementException   for a data template the caller does not own (404)
     * @throws IllegalArgumentException without a body (400)
     */
    @Transactional
    public DataMappingDto choose(int blueprintId, ChooseDataTemplateRequest request) {
        DocBlueprint blueprint = lock(ownership.requireTemplate(blueprintId));
        if (request == null) {
            throw new IllegalArgumentException("Send {\"dataTemplateId\": id} to choose a data template, or null "
                    + "to clear it.");
        }
        Integer wanted = request.dataTemplateId();
        DocDataTemplate template = null;
        String change;
        Map<String, String> mapping;
        if (wanted == null) {
            if (blueprint.getDataTemplateId() == null && blueprint.getDataMappingJson() == null) {
                return toDto(blueprint);
            }
            change = CHANGE_CLEARED;
            mapping = Map.of();
            blueprint.setDataTemplateId(null);
        } else {
            template = requireDataTemplate(wanted);
            if (wanted.equals(blueprint.getDataTemplateId())) {
                return toDto(blueprint);
            }
            change = CHANGE_CHOSEN;
            List<TemplateRegion> regions = currentRegions(blueprint);
            List<DocDataField> fields = fieldsOf(template);
            mapping = new LinkedHashMap<>();
            Map<String, String> suggested = new HashMap<>();
            for (BlueprintMappingSuggester.Candidate c : suggestions(regions, fields)) {
                suggested.put(c.regionKey(), c.fieldName());
            }
            for (TemplateRegion region : regions) {
                if (suggested.containsKey(region.key())) {
                    mapping.put(region.key(), suggested.get(region.key()));
                }
            }
            blueprint.setDataTemplateId(template.getDocDataTemplateId());
        }
        blueprint.setDataMappingJson(EffectiveRules.writeMapping(mapping));
        return saved(blueprint, template, mapping, change);
    }

    /**
     * {@code PUT /blueprints/{id}/data-mapping}: replaces the mapping.
     *
     * @throws IllegalArgumentException for an unknown region key or field name, or an incompatible kind (400)
     * @throws IllegalStateException    when no data template is chosen (409)
     */
    @Transactional
    public DataMappingDto saveMapping(int blueprintId, SaveDataMappingRequest request) {
        DocBlueprint blueprint = lock(ownership.requireTemplate(blueprintId));
        if (request == null) {
            throw new IllegalArgumentException("A mapping is required.");
        }
        DocDataTemplate template = chosenTemplate(blueprint).orElseThrow(() -> new IllegalStateException(
                "Choose a data template for this PDF template before mapping its fields."));
        Map<String, String> mapping = validMapping(request.mapping() == null ? Map.of() : request.mapping(),
                currentRegions(blueprint), fieldsOf(template));
        blueprint.setDataMappingJson(EffectiveRules.writeMapping(mapping));
        return saved(blueprint, template, mapping, CHANGE_MAPPING);
    }

    // ------------------------------------------------- shared with the list

    /** The step-3 counts of one template in {@code GET /blueprints}. */
    public record Counts(Integer dataTemplateId, String dataTemplateName, int mappedCount, int mappableCount) {

        static final Counts NONE = new Counts(null, null, 0, 0);
    }

    /**
     * The counts of several templates of the caller's bank with three
     * queries in all (data templates, their fields, the current regions),
     * keyed by template id; a template without a data template is absent.
     */
    @Transactional(readOnly = true)
    public Map<Integer, Counts> counts(List<DocBlueprint> owned) {
        List<DocBlueprint> withData = owned.stream().filter(b -> b.getDataTemplateId() != null).toList();
        if (withData.isEmpty()) {
            return Map.of();
        }
        int bankId = ownership.requireBankId();
        Set<Integer> templateIds = withData.stream().map(DocBlueprint::getDataTemplateId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Integer, String> names = new HashMap<>();
        for (DocDataTemplate template : dataTemplates.findAllById(templateIds)) {
            if (template.getBankId() == bankId) {
                names.put(template.getDocDataTemplateId(), template.getName());
            }
        }
        Map<Integer, List<DocDataField>> fieldsByTemplate = new HashMap<>();
        if (entityManager != null) {
            for (DocDataField field : entityManager.createQuery("select f from DocDataField f where f.bankId = :bankId"
                            + " and f.dataTemplateId in :ids", DocDataField.class)
                    .setParameter("bankId", bankId)
                    .setParameter("ids", templateIds)
                    .getResultList()) {
                fieldsByTemplate.computeIfAbsent(field.getDataTemplateId(), k -> new ArrayList<>()).add(field);
            }
        }
        Map<Integer, List<DocTemplateRegion>> rowsByBlueprint = regionRows.listByBlueprints(bankId,
                withData.stream().map(DocBlueprint::getDocBlueprintId).toList());
        Map<Integer, Counts> out = new HashMap<>();
        for (DocBlueprint blueprint : withData) {
            int version = TemplateVersions.effectiveVersion(blueprint);
            List<TemplateRegion> regions = rowsByBlueprint.getOrDefault(blueprint.getDocBlueprintId(), List.of())
                    .stream().filter(r -> r.getVersion() == version).map(OverlayMapper::toRegion).toList();
            List<DocDataField> fields = fieldsByTemplate.getOrDefault(blueprint.getDataTemplateId(), List.of());
            out.put(blueprint.getDocBlueprintId(), new Counts(blueprint.getDataTemplateId(),
                    names.get(blueprint.getDataTemplateId()),
                    mappedCount(regions, fields, EffectiveRules.readMapping(blueprint.getDataMappingJson())),
                    mappableCount(regions, fields)));
        }
        return out;
    }

    // ------------------------------------------------------ compatibility

    /** Whether a region of {@code regionKind} can show a data field of {@code fieldKind} (contract §3 table). */
    public static boolean compatible(RegionKind regionKind, String fieldKind) {
        if (regionKind == null || fieldKind == null) {
            return false;
        }
        String kind = fieldKind.strip().toUpperCase(Locale.ROOT);
        return switch (regionKind) {
            case TEXT -> Set.of("TEXT", "CHOICE", "NUMBER", "DATE").contains(kind);
            case MULTILINE -> Set.of("TEXT", "MULTILINE").contains(kind);
            case DATE -> Set.of("DATE", "TEXT").contains(kind);
            case NUMBER -> Set.of("NUMBER", "TEXT").contains(kind);
            case CHOICE -> Set.of("CHOICE", "TEXT").contains(kind);
            case CHECKBOX -> "CHECKBOX".equals(kind);
            case IMAGE, SIGNATURE -> "IMAGE".equals(kind);
        };
    }

    static int mappableCount(List<TemplateRegion> regions, List<DocDataField> fields) {
        return (int) regions.stream()
                .filter(r -> fields.stream().anyMatch(f -> compatible(r.kind(), f.getKind())))
                .count();
    }

    static int mappedCount(List<TemplateRegion> regions, List<DocDataField> fields, Map<String, String> mapping) {
        Map<String, DocDataField> byName = byName(fields);
        return (int) regions.stream().filter(r -> mappedField(r, byName, mapping) != null).count();
    }

    /** The data field mapped to a region, when it still exists and fits the region's kind. */
    private static String mappedField(TemplateRegion region, Map<String, DocDataField> fieldsByName,
                                      Map<String, String> mapping) {
        String name = mapping.get(region.key());
        DocDataField field = name == null ? null : fieldsByName.get(name);
        return field != null && compatible(region.kind(), field.getKind()) ? name : null;
    }

    // ------------------------------------------------------------ helpers

    private DataMappingDto saved(DocBlueprint blueprint, DocDataTemplate template, Map<String, String> mapping,
                                 String change) {
        blueprint.setRulesRevision(blueprint.getRulesRevision() + 1);
        blueprint.setUpdatedDate(LocalDateTime.now());
        blueprint = blueprints.save(blueprint);
        String managed = syncManagedSource(blueprint, template, mapping);
        DataMappingDto dto = toDto(blueprint);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("change", change);
        values.put("dataTemplateId", blueprint.getDataTemplateId());
        values.put("mapped", dto.mappedCount());
        values.put("unmapped", Math.max(0, dto.mappableCount() - dto.mappedCount()));
        values.put("managedSource", managed);
        values.put("revision", blueprint.getRulesRevision());
        audit.success(AuditActions.DOC_BLUEPRINT_DATA_MAPPING_SAVED, "DocBlueprint",
                String.valueOf(blueprint.getDocBlueprintId()), null, values);
        return dto;
    }

    /** Keeps the managed PRIMARY data source in step (class comment); returns what happened. */
    String syncManagedSource(DocBlueprint blueprint, DocDataTemplate template, Map<String, String> mapping) {
        List<DocBlueprintSource> all = sources.findByBankIdAndBlueprintIdOrderBySortOrderAscDocBlueprintSourceIdAsc(
                blueprint.getBankId(), blueprint.getDocBlueprintId());
        Optional<DocBlueprintSource> primary = all.stream().filter(DocBlueprintSource::isPrimary).findFirst();
        if (primary.isPresent() && !primary.get().isManaged()) {
            return SOURCE_HAND_MADE;
        }
        Integer flowTemplateId = template == null ? null : template.getSourceTemplateId();
        boolean readable = flowTemplateId != null && templates.getAccessible(flowTemplateId,
                currentUser.requireUserId(), currentUser.requireBankId()).isPresent();
        boolean takenByLinked = readable && all.stream()
                .anyMatch(s -> !s.isPrimary() && s.getTemplateId() == flowTemplateId);
        if (!readable || takenByLinked) {
            if (primary.isPresent()) {
                sources.delete(primary.get());
                sources.flush();
                return SOURCE_DELETED;
            }
            return SOURCE_NONE;
        }
        String json = BlueprintSourceUseCase.writeMappingJson(flowMapping(template, mapping, flowTemplateId));
        DocBlueprintSource source = primary.orElse(null);
        String outcome;
        if (source == null) {
            source = new DocBlueprintSource();
            source.setBankId(blueprint.getBankId());
            source.setBlueprintId(blueprint.getDocBlueprintId());
            source.setRole(DocBlueprintSource.ROLE_PRIMARY);
            source.setManaged(true);
            source.setSortOrder(all.stream().mapToInt(DocBlueprintSource::getSortOrder).min().orElse(1) - 1);
            outcome = SOURCE_CREATED;
        } else if (source.getTemplateId() == flowTemplateId && json.equals(source.getMappingJson())) {
            return SOURCE_UNCHANGED;
        } else {
            outcome = SOURCE_UPDATED;
        }
        source.setTemplateId(flowTemplateId);
        source.setMappingJson(json);
        source.setUpdatedAt(LocalDateTime.now());
        sources.saveAndFlush(source);
        return outcome;
    }

    /** Region → the flow field of the data field mapped to it (fields with a source that still exists). */
    private Map<String, String> flowMapping(DocDataTemplate template, Map<String, String> mapping, int flowTemplateId) {
        Map<String, DocDataField> byName = byName(fieldsOf(template));
        Set<String> flowFields = suggester.fields(flowTemplateId).stream().map(BlueprintMappingSuggester.Field::name)
                .collect(Collectors.toSet());
        Map<String, String> out = new LinkedHashMap<>();
        mapping.forEach((regionKey, fieldName) -> {
            DocDataField field = byName.get(fieldName);
            String flowField = field == null ? null : field.getSourceFieldName();
            if (flowField != null && !flowField.isBlank() && flowFields.contains(flowField)) {
                out.put(regionKey, flowField);
            }
        });
        return out;
    }

    private DataMappingDto toDto(DocBlueprint blueprint) {
        List<TemplateRegion> regions = currentRegions(blueprint);
        List<PageInfo> pages = TemplateVersions.pages(blueprint);
        Optional<DocDataTemplate> template = chosenTemplate(blueprint);
        List<DocDataField> fields = template.map(this::fieldsOf).orElse(List.of());
        Map<String, DocDataField> byName = byName(fields);
        Map<String, String> mapping = EffectiveRules.readMapping(blueprint.getDataMappingJson());
        Map<String, BlueprintMappingSuggester.Candidate> suggested = new HashMap<>();
        if (!fields.isEmpty()) {
            for (BlueprintMappingSuggester.Candidate c : suggestions(regions, fields)) {
                suggested.put(c.regionKey(), c);
            }
        }
        List<DataMappingDto.Row> rows = new ArrayList<>();
        int mapped = 0;
        for (TemplateRegion region : regions) {
            String field = mappedField(region, byName, mapping);
            if (field != null) {
                mapped++;
            }
            BlueprintMappingSuggester.Candidate c = suggested.get(region.key());
            RegionDto box = OverlayMapper.toDto(region, pages, null);
            rows.add(new DataMappingDto.Row(region.key(), region.label(), region.kind().name(), box.page(),
                    box.fx(), box.fy(), box.fw(), box.fh(), field,
                    c == null ? null : new DataMappingDto.Suggestion(c.fieldName(), c.score(), c.sameName())));
        }
        return new DataMappingDto(template.map(DocDataTemplate::getDocDataTemplateId).orElse(null),
                template.map(DocDataTemplate::getName).orElse(null), blueprint.getRulesRevision(), mapped,
                mappableCount(regions, fields), rows,
                fields.stream().map(f -> new DataMappingDto.Field(f.getFieldName(), f.getLabel(), f.getKind()))
                        .toList());
    }

    /** One-to-one suggestions over every region, kind-compatible by the contract §3 table. */
    private List<BlueprintMappingSuggester.Candidate> suggestions(List<TemplateRegion> regions,
                                                                  List<DocDataField> fields) {
        List<BlueprintMappingSuggester.Field> asFields = fields.stream()
                .map(f -> new BlueprintMappingSuggester.Field(f.getFieldName(), f.getKind(),
                        f.getLabel() == null ? List.of() : List.of(f.getLabel())))
                .toList();
        return suggester.suggestions(regions, asFields, Set.of(),
                (kind, field) -> compatible(kind, field.dataType()));
    }

    private static Map<String, String> validMapping(Map<String, String> requested, List<TemplateRegion> regions,
                                                    List<DocDataField> fields) {
        Map<String, TemplateRegion> regionsByKey = regions.stream()
                .collect(Collectors.toMap(TemplateRegion::key, Function.identity(), (a, b) -> a));
        Map<String, DocDataField> fieldsByName = byName(fields);
        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String regionKey = entry.getKey() == null ? "" : entry.getKey().strip();
            TemplateRegion region = regionsByKey.get(regionKey);
            if (region == null) {
                throw new IllegalArgumentException("Unknown field \"" + truncate(regionKey)
                        + "\" of the PDF template.");
            }
            String fieldName = entry.getValue() == null ? "" : entry.getValue().strip();
            if (fieldName.isEmpty()) {
                continue;
            }
            DocDataField field = fieldsByName.get(fieldName);
            if (field == null) {
                throw new IllegalArgumentException("\"" + truncate(fieldName) + "\" is not a field of the data "
                        + "template.");
            }
            if (!compatible(region.kind(), field.getKind())) {
                throw new IllegalArgumentException("Field \"" + regionKey + "\" ("
                        + region.kind().name().toLowerCase(Locale.ROOT) + ") cannot show the data field \""
                        + fieldName + "\" (" + String.valueOf(field.getKind()).toLowerCase(Locale.ROOT) + ").");
            }
            mapping.put(regionKey, fieldName);
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (TemplateRegion region : regions) {
            if (mapping.containsKey(region.key())) {
                ordered.put(region.key(), mapping.get(region.key()));
            }
        }
        return ordered;
    }

    /** The caller's data template (owner-scoped, same bank); 404 otherwise. */
    private DocDataTemplate requireDataTemplate(int id) {
        return dataTemplates.findByDocDataTemplateIdAndBankIdAndOwnerUserId(id, ownership.requireBankId(),
                        ownership.requireUserId())
                .orElseThrow(() -> new NoSuchElementException("Data template " + id + " not found."));
    }

    private Optional<DocDataTemplate> chosenTemplate(DocBlueprint blueprint) {
        if (blueprint.getDataTemplateId() == null) {
            return Optional.empty();
        }
        return dataTemplates.findByDocDataTemplateIdAndBankId(blueprint.getDataTemplateId(), blueprint.getBankId());
    }

    private List<DocDataField> fieldsOf(DocDataTemplate template) {
        return dataFields.findByBankIdAndDataTemplateIdOrderBySortOrderAscDocDataFieldIdAsc(template.getBankId(),
                template.getDocDataTemplateId());
    }

    private static Map<String, DocDataField> byName(List<DocDataField> fields) {
        Map<String, DocDataField> out = new LinkedHashMap<>();
        fields.forEach(f -> out.putIfAbsent(f.getFieldName(), f));
        return out;
    }

    private List<TemplateRegion> currentRegions(DocBlueprint blueprint) {
        List<TemplateRegion> regions = new ArrayList<>(
                versions.regions(blueprint, TemplateVersions.effectiveVersion(blueprint)));
        regions.sort(Comparator.comparingInt(TemplateRegion::sortOrder));
        return regions;
    }

    /** Locks the template row (SELECT … FOR UPDATE) so mapping saves and rule saves run one after the other. */
    private DocBlueprint lock(DocBlueprint blueprint) {
        if (entityManager != null && entityManager.contains(blueprint)) {
            entityManager.refresh(blueprint, LockModeType.PESSIMISTIC_WRITE);
        }
        return blueprint;
    }

    private static String truncate(String value) {
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}
