package com.modocument.doccreation.usecase;

import com.modocument.application.audit.AuditActions;
import com.modocument.application.port.AuditRecorder;
import com.modocument.doccreation.dto.FillFromRecordRequest;
import com.modocument.doccreation.dto.FillPreviewDto;
import com.modocument.doccreation.dto.InstanceDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocBlueprintSource;
import com.modocument.doccreation.entity.DocInstance;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fills a document from a validated record (docs/doccreation/flow-records-contract.md
 * §4): {@link #preview} shows what would be written, {@link #fill} writes the
 * user's choice through the document's ordinary value merge.
 *
 * <p>MAPPING — when the record's template is a saved source of the
 * document's template, its saved mapping applies ({@code SAVED}) and the
 * other saved sources are resolved through their link fields: for a LINKED
 * source, the visible record of its template whose key
 * {@code linkFieldThis} equals the primary record's normalised
 * {@code linkFieldPrimary} value (newest wins, the others are listed as
 * alternatives). When the picked record is itself a LINKED source's, the
 * primary record is found the same way in reverse first. Otherwise the
 * mapping is built on the fly by auto-mapping ({@code AUTO}).
 *
 * <p>Values are what the caller may see: masked per role exactly as the
 * record search shows them (the REPORT masking applies again when the PDF
 * is generated). Keys are compared on the raw values server side only.
 */
@Service
public class DocFillUseCase {

    public static final String MAPPING_SAVED = "SAVED";
    public static final String MAPPING_AUTO = "AUTO";
    public static final String OVERWRITE_EMPTY_ONLY = "EMPTY_ONLY";
    public static final String OVERWRITE_ALL = "ALL";

    private final DocCreationOwnership ownership;
    private final DocInstanceUseCase instances;
    private final TemplateVersions templates;
    private final TemplateRecordUseCase records;
    private final BlueprintSourceUseCase sources;
    private final BlueprintMappingSuggester suggester;
    private final AuditRecorder audit;

    public DocFillUseCase(DocCreationOwnership ownership,
                          DocInstanceUseCase instances,
                          TemplateVersions templates,
                          TemplateRecordUseCase records,
                          BlueprintSourceUseCase sources,
                          BlueprintMappingSuggester suggester,
                          AuditRecorder audit) {
        this.ownership = ownership;
        this.instances = instances;
        this.templates = templates;
        this.records = records;
        this.sources = sources;
        this.suggester = suggester;
        this.audit = audit;
    }

    /** {@code GET /instances/{id}/fill-preview?recordId=}. */
    public FillPreviewDto preview(int instanceId, long recordId) {
        Plan plan = plan(instanceId, recordId, Map.of(), true);
        audit.success(AuditActions.SENSITIVE_DATA_VIEWED, "TemplateRecord", String.valueOf(recordId), null,
                Map.of("view", "fill-preview"));
        return plan.preview();
    }

    /**
     * {@code POST /instances/{id}/fill-from-record}: writes the previewed
     * values the user chose ({@code fields}, all by default) — with
     * {@code EMPTY_ONLY} (the default) only into empty fields, with
     * {@code ALL} over existing values too — and audits
     * DOC_FILLED_FROM_RECORD with region keys and record ids only.
     */
    public InstanceDto fill(int instanceId, FillFromRecordRequest request) {
        if (request == null || request.recordId() == null) {
            throw new IllegalArgumentException("recordId is required.");
        }
        String overwrite = request.overwrite() == null || request.overwrite().isBlank() ? OVERWRITE_EMPTY_ONLY
                : request.overwrite().strip().toUpperCase(Locale.ROOT);
        if (!OVERWRITE_EMPTY_ONLY.equals(overwrite) && !OVERWRITE_ALL.equals(overwrite)) {
            throw new IllegalArgumentException("overwrite must be EMPTY_ONLY or ALL.");
        }
        Map<Integer, Long> choices = new HashMap<>();
        if (request.linkedRecordIds() != null) {
            request.linkedRecordIds().forEach((templateId, recordId) -> {
                try {
                    if (recordId != null) {
                        choices.put(Integer.parseInt(templateId.strip()), recordId);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("linkedRecordIds is keyed by template id.");
                }
            });
        }
        Plan plan = plan(instanceId, request.recordId(), choices, false);
        Set<String> selected = null;
        if (request.fields() != null) {
            selected = new LinkedHashSet<>();
            for (String key : request.fields()) {
                String regionKey = key == null ? "" : key.strip();
                if (!plan.regionKeys().contains(regionKey)) {
                    throw new IllegalArgumentException("Unknown field \"" + truncate(regionKey) + "\".");
                }
                selected.add(regionKey);
            }
        }
        Map<String, String> changes = new LinkedHashMap<>();
        Set<Integer> contributing = new HashSet<>();
        for (FillPreviewDto.FieldDto field : plan.preview().fields()) {
            if (selected != null && !selected.contains(field.regionKey())) {
                continue;
            }
            if (OVERWRITE_EMPTY_ONLY.equals(overwrite) && !field.currentValue().isEmpty()) {
                continue;
            }
            changes.put(field.regionKey(), field.value());
            contributing.add(field.sourceTemplateId());
        }
        List<DocInstanceUseCase.SourceRef> used = new ArrayList<>();
        for (Applied applied : plan.applied()) {
            if (applied.anchor() || contributing.contains(applied.record().templateId())) {
                used.add(new DocInstanceUseCase.SourceRef(applied.record().id(), applied.record().templateId(),
                        applied.role()));
            }
        }
        InstanceDto dto = instances.fillValues(instanceId, changes, used);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("recordIds", used.stream().map(DocInstanceUseCase.SourceRef::recordId).toList());
        evidence.put("regionKeys", new ArrayList<>(changes.keySet()));
        evidence.put("overwrite", overwrite);
        evidence.put("mappingSource", plan.preview().mappingSource());
        audit.success(AuditActions.DOC_FILLED_FROM_RECORD, "DocInstance", String.valueOf(instanceId), null,
                evidence);
        return dto;
    }

    // -------------------------------------------------------------- the plan

    /** A record applied with a mapping; the anchor is the record the user picked. */
    private record Applied(TemplateRecordUseCase.VisibleRecord record, Map<String, String> mapping, String role,
                           boolean anchor) {
    }

    private record Plan(FillPreviewDto preview, List<Applied> applied, Set<String> regionKeys) {
    }

    /** With {@code checks} (the preview), each field carries the checks its value would fail (ws34). */
    private Plan plan(int instanceId, long recordId, Map<Integer, Long> choices, boolean checks) {
        records.requireIngestionRead();
        DocInstance instance = ownership.requireInstance(instanceId);
        DocBlueprint blueprint = ownership.requireTemplate(instance.getBlueprintId());
        TemplateRecordUseCase.VisibleRecord anchor = records.requireVisible(recordId);
        List<TemplateRegion> regions = templates.regions(blueprint, instance.getBlueprintVersion());
        Map<String, String> current = DocInstanceUseCase.readValues(instance);

        List<DocBlueprintSource> saved = sources.sourcesOf(blueprint);
        Optional<DocBlueprintSource> anchorSource = saved.stream()
                .filter(s -> s.getTemplateId() == anchor.templateId()).findFirst();
        List<Applied> applied = new ArrayList<>();
        List<FillPreviewDto.LinkedDto> linked = new ArrayList<>();
        String mappingSource;
        if (anchorSource.isPresent()) {
            mappingSource = MAPPING_SAVED;
            DocBlueprintSource own = anchorSource.get();
            applied.add(new Applied(anchor, BlueprintSourceUseCase.readMapping(own), own.getRole(), true));
            TemplateRecordUseCase.VisibleRecord primaryRecord = anchor;
            if (!own.isPrimary()) {
                Optional<DocBlueprintSource> primary = saved.stream().filter(DocBlueprintSource::isPrimary)
                        .findFirst();
                primaryRecord = null;
                if (primary.isPresent()) {
                    Resolved found = resolve(primary.get().getTemplateId(), own.getLinkFieldPrimary(),
                            anchor.raw().get(own.getLinkFieldThis()), choices);
                    linked.add(found.dto(primary.get().getTemplateId()));
                    if (found.chosen() != null) {
                        primaryRecord = found.chosen();
                        applied.add(new Applied(found.chosen(), BlueprintSourceUseCase.readMapping(primary.get()),
                                DocBlueprintSource.ROLE_PRIMARY, false));
                    }
                }
            }
            for (DocBlueprintSource source : saved) {
                if (source.isPrimary() || source == own) {
                    continue;
                }
                Resolved found = primaryRecord == null
                        ? Resolved.none(source.getLinkFieldThis(), records.readableTemplateName(source.getTemplateId()))
                        : resolve(source.getTemplateId(), source.getLinkFieldThis(),
                        primaryRecord.raw().get(source.getLinkFieldPrimary()), choices);
                linked.add(found.dto(source.getTemplateId()));
                if (found.chosen() != null) {
                    applied.add(new Applied(found.chosen(), BlueprintSourceUseCase.readMapping(source),
                            DocBlueprintSource.ROLE_LINKED, false));
                }
            }
        } else {
            mappingSource = MAPPING_AUTO;
            applied.add(new Applied(anchor, suggester.autoMap(regions, suggester.fields(anchor.templateId())),
                    DocBlueprintSource.ROLE_PRIMARY, true));
        }
        for (Map.Entry<Integer, Long> choice : choices.entrySet()) {
            boolean known = linked.stream().anyMatch(l -> l.templateId() == choice.getKey());
            if (!known) {
                throw new IllegalArgumentException("Template " + choice.getKey()
                        + " is not a linked data source of this document.");
            }
        }

        Map<String, FillPreviewDto.FieldDto> byRegion = new HashMap<>();
        for (Applied source : applied) {
            for (TemplateRegion region : regions) {
                if (byRegion.containsKey(region.key())
                        || region.kind() == RegionKind.IMAGE || region.kind() == RegionKind.SIGNATURE) {
                    continue;
                }
                String fieldName = source.mapping().get(region.key());
                String value = fieldName == null ? null : source.record().masked().get(fieldName);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String currentValue = current.getOrDefault(region.key(), "");
                byRegion.put(region.key(), new FillPreviewDto.FieldDto(region.key(), region.label(), value,
                        currentValue, source.record().templateId(), fieldName,
                        !currentValue.isEmpty() && !currentValue.equals(value),
                        RecordText.sameName(fieldName, region.key(), region.label()), List.of()));
            }
        }
        Map<String, List<FillPreviewDto.CheckDto>> failing = Map.of();
        if (checks && !byRegion.isEmpty()) {
            Map<String, String> proposed = new LinkedHashMap<>();
            byRegion.forEach((key, field) -> proposed.put(key, field.value()));
            failing = instances.wouldFail(instance, blueprint, proposed);
        }
        List<FillPreviewDto.FieldDto> fields = new ArrayList<>();
        Set<String> regionKeys = new LinkedHashSet<>();
        for (TemplateRegion region : regions) {
            regionKeys.add(region.key());
            FillPreviewDto.FieldDto f = byRegion.get(region.key());
            if (f != null) {
                fields.add(new FillPreviewDto.FieldDto(f.regionKey(), f.label(), f.value(), f.currentValue(),
                        f.sourceTemplateId(), f.sourceField(), f.willOverwrite(), f.sameName(),
                        failing.getOrDefault(region.key(), List.of())));
            }
        }
        return new Plan(new FillPreviewDto(anchor.id(), anchor.templateId(), mappingSource, linked, fields),
                applied, regionKeys);
    }

    /** The records of a linked template matched by key: the chosen one (or none) and the rest. */
    private record Resolved(String matchedBy, String templateName, TemplateRecordUseCase.VisibleRecord chosen,
                            List<Long> alternatives) {

        static Resolved none(String matchedBy, String templateName) {
            return new Resolved(matchedBy, templateName, null, List.of());
        }

        FillPreviewDto.LinkedDto dto(int templateId) {
            return new FillPreviewDto.LinkedDto(templateId, templateName, matchedBy,
                    chosen == null ? null : chosen.id(), alternatives);
        }
    }

    private Resolved resolve(int templateId, String fieldName, String rawValue, Map<Integer, Long> choices) {
        List<TemplateRecordUseCase.VisibleRecord> matches =
                records.findByKey(templateId, fieldName, RecordText.key(rawValue));
        if (matches.isEmpty()) {
            if (choices.containsKey(templateId)) {
                throw new IllegalArgumentException("Record " + choices.get(templateId)
                        + " is not a match of linked template " + templateId + ".");
            }
            return Resolved.none(fieldName, records.readableTemplateName(templateId));
        }
        TemplateRecordUseCase.VisibleRecord chosen = matches.get(0);
        Long choice = choices.get(templateId);
        if (choice != null) {
            chosen = matches.stream().filter(m -> m.id() == choice).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Record " + choice
                            + " is not a match of linked template " + templateId + "."));
        }
        long chosenId = chosen.id();
        List<Long> alternatives = matches.stream().map(TemplateRecordUseCase.VisibleRecord::id)
                .filter(id -> id != chosenId).toList();
        return new Resolved(fieldName, chosen.template().getName(), chosen, alternatives);
    }

    private static String truncate(String value) {
        return value.length() <= 80 ? value : value.substring(0, 80);
    }
}
