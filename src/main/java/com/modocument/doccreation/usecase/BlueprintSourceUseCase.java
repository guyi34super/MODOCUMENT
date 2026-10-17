package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.doccreation.dto.BlueprintSourceDto;
import com.modocument.doccreation.dto.CreateBlueprintSourceRequest;
import com.modocument.doccreation.dto.UpdateBlueprintSourceRequest;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSource;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.doccreation.repo.DocBlueprintSourceJpaRepository;
import com.modocument.domain.entity.Template;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The saved data sources of a PDF template (docs/doccreation/flow-records-contract.md
 * §4): flow templates whose records fill the template, each with a mapping
 * (region key to template field name). One PRIMARY per template; LINKED
 * sources join the primary record through their link fields.
 *
 * <p>The caller must own the template (blueprint, {@link DocCreationOwnership})
 * and be able to read each flow template ({@link TemplateOwnership#requireReadable}).
 * The mapping is checked against the regions of the blueprint's current
 * version. Every change is audited DOC_BLUEPRINT_MAPPING_SAVED with counts
 * only.
 */
@Service
public class BlueprintSourceUseCase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAPPING = new TypeReference<>() {
    };

    private final DocCreationOwnership ownership;
    private final DocBlueprintSourceJpaRepository sources;
    private final TemplateOwnership templateOwnership;
    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;
    private final TemplateVersions versions;
    private final BlueprintMappingSuggester suggester;
    private final AuditRecorder audit;

    public BlueprintSourceUseCase(DocCreationOwnership ownership,
                                  DocBlueprintSourceJpaRepository sources,
                                  TemplateOwnership templateOwnership,
                                  TemplateRepository templates,
                                  CurrentUserProvider currentUser,
                                  TemplateVersions versions,
                                  BlueprintMappingSuggester suggester,
                                  AuditRecorder audit) {
        this.ownership = ownership;
        this.sources = sources;
        this.templateOwnership = templateOwnership;
        this.templates = templates;
        this.currentUser = currentUser;
        this.versions = versions;
        this.suggester = suggester;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- API

    /** {@code GET /blueprints/{id}/sources}. */
    public List<BlueprintSourceDto> list(int blueprintId) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        List<TemplateRegion> regions = currentRegions(blueprint);
        return sourcesOf(blueprint).stream().map(source -> toDto(source, regions)).toList();
    }

    /**
     * {@code POST /blueprints/{id}/sources}: 409 for a second PRIMARY or a
     * template already linked, 400 for a LINKED source without (known) link
     * fields. The mapping starts as the auto-mapping.
     */
    @Transactional
    public BlueprintSourceDto create(int blueprintId, CreateBlueprintSourceRequest request) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        if (request == null || request.templateId() == null) {
            throw new IllegalArgumentException("templateId is required.");
        }
        String role = parseRole(request.role());
        Template template = templateOwnership.requireReadable(request.templateId());
        List<DocBlueprintSource> existing = sourcesOf(blueprint);
        if (existing.stream().anyMatch(s -> s.getTemplateId() == template.getTemplateId())) {
            throw new IllegalStateException("Template " + template.getTemplateId()
                    + " is already a data source of this template.");
        }
        Optional<DocBlueprintSource> primary = existing.stream().filter(DocBlueprintSource::isPrimary).findFirst();
        if (DocBlueprintSource.ROLE_PRIMARY.equals(role) && primary.isPresent()) {
            throw new IllegalStateException("This template already has a primary data source; add further "
                    + "flows as LINKED sources.");
        }
        List<BlueprintMappingSuggester.Field> fields = suggester.fields(template.getTemplateId());

        DocBlueprintSource source = new DocBlueprintSource();
        source.setBankId(blueprint.getBankId());
        source.setBlueprintId(blueprintId);
        source.setTemplateId(template.getTemplateId());
        source.setRole(role);
        if (DocBlueprintSource.ROLE_LINKED.equals(role)) {
            DocBlueprintSource primarySource = primary.orElseThrow(() -> new IllegalArgumentException(
                    "Add the primary data source first; a LINKED source is joined to it."));
            setLinkFields(source, primarySource, fields, request.linkFieldPrimary(), request.linkFieldThis(),
                    true);
        }
        List<TemplateRegion> regions = currentRegions(blueprint);
        Map<String, String> mapping = suggester.autoMap(regions, fields);
        source.setMappingJson(writeMapping(mapping));
        source.setSortOrder(existing.stream().mapToInt(DocBlueprintSource::getSortOrder).max().orElse(-1) + 1);
        source.setUpdatedAt(LocalDateTime.now());
        DocBlueprintSource saved = saveUnique(source);
        auditSaved(blueprintId, saved, mapping.size(), "CREATED");
        return toDto(saved, regions);
    }

    /**
     * {@code PUT /blueprints/{id}/sources/{sourceId}}: 400 for an unknown
     * region or field, an IMAGE or SIGNATURE region, or (LINKED) missing or
     * unknown link fields. One template field may feed several regions.
     */
    @Transactional
    public BlueprintSourceDto update(int blueprintId, long sourceId, UpdateBlueprintSourceRequest request) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        DocBlueprintSource source = requireSource(blueprint, sourceId);
        templateOwnership.requireReadable(source.getTemplateId());
        if (request == null) {
            throw new IllegalArgumentException("A mapping is required.");
        }
        List<TemplateRegion> regions = currentRegions(blueprint);
        List<BlueprintMappingSuggester.Field> fields = suggester.fields(source.getTemplateId());
        Map<String, String> mapping = request.mapping() == null ? readMapping(source)
                : validMapping(request.mapping(), regions, fields);
        if (!source.isPrimary()) {
            DocBlueprintSource primary = sourcesOf(blueprint).stream().filter(DocBlueprintSource::isPrimary)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "This template has no primary data source to link to."));
            setLinkFields(source, primary, fields,
                    request.linkFieldPrimary() == null ? source.getLinkFieldPrimary() : request.linkFieldPrimary(),
                    request.linkFieldThis() == null ? source.getLinkFieldThis() : request.linkFieldThis(), true);
        }
        source.setMappingJson(writeMapping(mapping));
        source.setUpdatedAt(LocalDateTime.now());
        DocBlueprintSource saved = sources.save(source);
        auditSaved(blueprintId, saved, mapping.size(), "UPDATED");
        return toDto(saved, regions);
    }

    /** {@code DELETE /blueprints/{id}/sources/{sourceId}}: the PRIMARY only once no LINKED source is left (409). */
    @Transactional
    public void delete(int blueprintId, long sourceId) {
        DocBlueprint blueprint = ownership.requireTemplate(blueprintId);
        DocBlueprintSource source = requireSource(blueprint, sourceId);
        if (source.isPrimary() && sourcesOf(blueprint).stream().anyMatch(s -> !s.isPrimary())) {
            throw new IllegalStateException("Remove the linked data sources before the primary one.");
        }
        sources.delete(source);
        auditSaved(blueprintId, source, 0, "DELETED");
    }

    // ---------------------------------------------------- shared with fill

    /** The template's saved sources, PRIMARY first then in their order. */
    public List<DocBlueprintSource> sourcesOf(DocBlueprint blueprint) {
        List<DocBlueprintSource> list = new ArrayList<>(
                sources.findByBankIdAndBlueprintIdOrderBySortOrderAscDocBlueprintSourceIdAsc(
                        blueprint.getBankId(), blueprint.getDocBlueprintId()));
        list.sort((a, b) -> Boolean.compare(b.isPrimary(), a.isPrimary()));
        return list;
    }

    /** A source's saved mapping (region key to field name). */
    public static Map<String, String> readMapping(DocBlueprintSource source) {
        if (source.getMappingJson() == null || source.getMappingJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, String> mapping = JSON.readValue(source.getMappingJson(), MAPPING);
            if (mapping == null) {
                return new LinkedHashMap<>();
            }
            mapping.values().removeIf(v -> v == null || v.isBlank());
            return mapping;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------ helpers

    private List<TemplateRegion> currentRegions(DocBlueprint blueprint) {
        return versions.regions(blueprint, TemplateVersions.effectiveVersion(blueprint));
    }

    private DocBlueprintSource requireSource(DocBlueprint blueprint, long sourceId) {
        return sources.findByDocBlueprintSourceIdAndBankIdAndBlueprintId(sourceId, blueprint.getBankId(),
                        blueprint.getDocBlueprintId())
                .orElseThrow(() -> new NoSuchElementException("Data source " + sourceId + " not found."));
    }

    private static String parseRole(String role) {
        String value = role == null || role.isBlank() ? DocBlueprintSource.ROLE_PRIMARY
                : role.strip().toUpperCase(Locale.ROOT);
        if (!DocBlueprintSource.ROLE_PRIMARY.equals(value) && !DocBlueprintSource.ROLE_LINKED.equals(value)) {
            throw new IllegalArgumentException("role must be PRIMARY or LINKED.");
        }
        return value;
    }

    /** Sets (and checks) a LINKED source's link fields: both required, each a field of its template. */
    private void setLinkFields(DocBlueprintSource source, DocBlueprintSource primary,
                               List<BlueprintMappingSuggester.Field> thisFields,
                               String linkFieldPrimary, String linkFieldThis, boolean required) {
        String onPrimary = linkFieldPrimary == null ? null : linkFieldPrimary.strip();
        String onThis = linkFieldThis == null ? null : linkFieldThis.strip();
        if (required && (onPrimary == null || onPrimary.isEmpty() || onThis == null || onThis.isEmpty())) {
            throw new IllegalArgumentException("A LINKED source needs linkFieldPrimary and linkFieldThis "
                    + "(the fields that hold the same key in both flows).");
        }
        Set<String> primaryFields = suggester.fields(primary.getTemplateId()).stream()
                .map(BlueprintMappingSuggester.Field::name).collect(Collectors.toSet());
        if (!primaryFields.contains(onPrimary)) {
            throw new IllegalArgumentException("\"" + truncate(onPrimary) + "\" is not a field of the primary "
                    + "data source's template.");
        }
        if (thisFields.stream().map(BlueprintMappingSuggester.Field::name).noneMatch(onThis::equals)) {
            throw new IllegalArgumentException("\"" + truncate(onThis) + "\" is not a field of template "
                    + source.getTemplateId() + ".");
        }
        source.setLinkFieldPrimary(onPrimary);
        source.setLinkFieldThis(onThis);
    }

    private static Map<String, String> validMapping(Map<String, String> requested, List<TemplateRegion> regions,
                                                    List<BlueprintMappingSuggester.Field> fields) {
        Map<String, TemplateRegion> byKey = regions.stream()
                .collect(Collectors.toMap(TemplateRegion::key, Function.identity(), (a, b) -> a));
        Set<String> fieldNames = fields.stream().map(BlueprintMappingSuggester.Field::name)
                .collect(Collectors.toSet());
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String regionKey = entry.getKey() == null ? "" : entry.getKey().strip();
            TemplateRegion region = byKey.get(regionKey);
            if (region == null) {
                throw new IllegalArgumentException("Unknown field \"" + truncate(regionKey) + "\" of the template.");
            }
            String fieldName = entry.getValue() == null ? null : entry.getValue().strip();
            if (fieldName == null || fieldName.isEmpty()) {
                continue;
            }
            if (region.kind() == RegionKind.IMAGE || region.kind() == RegionKind.SIGNATURE) {
                throw new IllegalArgumentException("Field \"" + regionKey + "\" takes a photo or signature and "
                        + "cannot be filled from data.");
            }
            if (!fieldNames.contains(fieldName)) {
                throw new IllegalArgumentException("\"" + truncate(fieldName) + "\" is not a field of the flow "
                        + "template.");
            }
            mapping.put(regionKey, fieldName);
        }
        // Region order, so the stored JSON reads like the template.
        Map<String, String> ordered = new LinkedHashMap<>();
        for (TemplateRegion region : regions) {
            if (mapping.containsKey(region.key())) {
                ordered.put(region.key(), mapping.get(region.key()));
            }
        }
        return ordered;
    }

    private DocBlueprintSource saveUnique(DocBlueprintSource source) {
        try {
            return sources.saveAndFlush(source);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This data source was added meanwhile; reload the template.", e);
        }
    }

    private BlueprintSourceDto toDto(DocBlueprintSource source, List<TemplateRegion> regions) {
        Optional<Template> template = templates.getAccessible(source.getTemplateId(), currentUser.requireUserId(),
                currentUser.requireBankId());
        Map<String, String> mapping = readMapping(source);
        if (template.isEmpty()) {
            return new BlueprintSourceDto(source.getDocBlueprintSourceId(), source.getTemplateId(), null,
                    source.getRole(), source.getLinkFieldPrimary(), source.getLinkFieldThis(), mapping,
                    List.of(), List.of(), false, source.isManaged());
        }
        List<BlueprintMappingSuggester.Field> fields = suggester.fields(source.getTemplateId());
        List<BlueprintSourceDto.SuggestionDto> suggestions = suggester.suggestions(regions, fields, mapping.keySet())
                .stream()
                .map(c -> new BlueprintSourceDto.SuggestionDto(c.regionKey(), c.fieldName(), c.score(), c.sameName()))
                .toList();
        return new BlueprintSourceDto(source.getDocBlueprintSourceId(), source.getTemplateId(),
                template.get().getName(), source.getRole(), source.getLinkFieldPrimary(), source.getLinkFieldThis(),
                mapping,
                fields.stream().map(f -> new BlueprintSourceDto.FieldDto(f.name(), f.dataType())).toList(),
                suggestions, true, source.isManaged());
    }

    private void auditSaved(int blueprintId, DocBlueprintSource source, int mappedRegions, String change) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("change", change);
        values.put("sourceId", source.getDocBlueprintSourceId());
        values.put("templateId", source.getTemplateId());
        values.put("role", source.getRole());
        values.put("mappedRegions", mappedRegions);
        values.put("linked", !source.isPrimary());
        audit.success(AuditActions.DOC_BLUEPRINT_MAPPING_SAVED, "DocBlueprint", String.valueOf(blueprintId), null,
                values);
    }

    /** A source's mapping as stored ({@code MappingJson}, never null). */
    static String writeMappingJson(Map<String, String> mapping) {
        return writeMapping(mapping);
    }

    private static String writeMapping(Map<String, String> mapping) {
        try {
            return JSON.writeValueAsString(mapping);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the mapping.", e);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}
