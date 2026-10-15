package com.modocument.doccreation.usecase;

import com.modocument.application.mapping.ColumnNameSimilarity;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.MappingSettings;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Auto-mapping of a PDF template's regions to a flow template's fields
 * (docs/doccreation/flow-records-contract.md §4, code only, no AI):
 *
 * <ul>
 *   <li>A field whose normalised name equals the region's key or label
 *       scores 1.0 ({@code sameName}).</li>
 *   <li>Otherwise the score is the higher of
 *       {@link ColumnNameSimilarity#score(String, String, Collection, Map)}
 *       against the region's label and against its key — with the field's own
 *       aliases ({@code TemplateFields.getAliases()}) and the bank's
 *       {@code mapping.aliases}.</li>
 *   <li>Only compatible pairs count: a DATE region takes a date-like or Text
 *       field, a NUMBER region a numeric or Text field, CHECKBOX, TEXT and
 *       MULTILINE any field; IMAGE and SIGNATURE are never mapped.</li>
 *   <li>Assignment is one-to-one, best score first, at the bank's
 *       {@code mapping.confidenceThreshold} (0.7 shipped).</li>
 * </ul>
 */
@Component
public class BlueprintMappingSuggester {

    private static final Logger log = LoggerFactory.getLogger(BlueprintMappingSuggester.class);
    /** The shipped {@code mapping.confidence-threshold}. */
    static final double DEFAULT_THRESHOLD = 0.7;

    private final MappingSettings mappingSettings;
    private final TemplateFieldsRepository templateFields;
    private final TemplateDataTypeRepository dataTypes;

    public BlueprintMappingSuggester(MappingSettings mappingSettings,
                                     TemplateFieldsRepository templateFields,
                                     TemplateDataTypeRepository dataTypes) {
        this.mappingSettings = mappingSettings;
        this.templateFields = templateFields;
        this.dataTypes = dataTypes;
    }

    /** A flow template field as the suggester sees it. */
    public record Field(String name, String dataType, List<String> aliases) {
    }

    /** A scored region/field pair. */
    public record Candidate(String regionKey, String fieldName, double score, boolean sameName) {
    }

    /** The bank's knobs: the threshold and the extra header aliases. */
    public record Settings(double threshold, Map<String, String> aliases) {
    }

    // ------------------------------------------------------------ wiring

    /** The threshold and aliases of the caller's bank (shipped defaults when unreadable). */
    public Settings settings() {
        try {
            MappingProperties properties = mappingSettings.forCurrentBank();
            return new Settings(properties.getConfidenceThreshold(),
                    properties.getAliases() == null ? Map.of() : properties.getAliases());
        } catch (RuntimeException e) {
            log.debug("Mapping settings unreadable ({}); using the shipped threshold", e.getClass().getSimpleName());
            return new Settings(DEFAULT_THRESHOLD, Map.of());
        }
    }

    /** The template's fields with their data type names, in field order. */
    public List<Field> fields(int templateId) {
        Map<Integer, String> typeNames = new HashMap<>();
        for (TemplateDataType type : dataTypes.list()) {
            typeNames.put(type.getTemplateDataTypeId(), type.getDataType());
        }
        List<Field> fields = new ArrayList<>();
        for (TemplateFields field : templateFields.listByTemplateId(templateId)) {
            fields.add(new Field(field.getFieldName(),
                    typeNames.getOrDefault(field.getTemplateDataTypeId(), "Text"),
                    field.getAliases()));
        }
        return fields;
    }

    /** The automatic mapping (region key to field name) of {@code regions} to {@code fields}. */
    public Map<String, String> autoMap(List<TemplateRegion> regions, List<Field> fields) {
        Settings settings = settings();
        return assign(regions, candidates(regions, fields, settings.aliases()), settings.threshold());
    }

    /** Suggestions for the regions not in {@code mappedRegionKeys} (one-to-one among them). */
    public List<Candidate> suggestions(List<TemplateRegion> regions, List<Field> fields,
                                       Set<String> mappedRegionKeys) {
        Settings settings = settings();
        List<TemplateRegion> open = regions.stream().filter(r -> !mappedRegionKeys.contains(r.key())).toList();
        List<Candidate> all = candidates(open, fields, settings.aliases());
        Map<String, String> assigned = assign(open, all, settings.threshold());
        Map<String, Candidate> byPair = new HashMap<>();
        for (Candidate candidate : all) {
            byPair.put(candidate.regionKey() + "\u0000" + candidate.fieldName(), candidate);
        }
        List<Candidate> out = new ArrayList<>();
        assigned.forEach((region, field) -> out.add(byPair.get(region + "\u0000" + field)));
        return out;
    }

    // ------------------------------------------------------------ the rules

    /** Every compatible pair with a positive score. */
    static List<Candidate> candidates(List<TemplateRegion> regions, List<Field> fields,
                                      Map<String, String> bankAliases) {
        List<Candidate> candidates = new ArrayList<>();
        for (TemplateRegion region : regions) {
            for (Field field : fields) {
                if (field.name() == null || !compatible(region.kind(), field.dataType())) {
                    continue;
                }
                if (RecordText.sameName(field.name(), region.key(), region.label())) {
                    candidates.add(new Candidate(region.key(), field.name(), 1.0, true));
                    continue;
                }
                double score = Math.max(
                        ColumnNameSimilarity.score(region.label(), field.name(), field.aliases(), bankAliases),
                        ColumnNameSimilarity.score(region.key(), field.name(), field.aliases(), bankAliases));
                if (score > 0) {
                    candidates.add(new Candidate(region.key(), field.name(), round(score), false));
                }
            }
        }
        return candidates;
    }

    /**
     * One-to-one, best score first (same-name first among equals, then the
     * regions' and fields' order), keeping pairs at or above the threshold.
     * The result is in region order.
     */
    static Map<String, String> assign(List<TemplateRegion> regions, List<Candidate> candidates, double threshold) {
        Map<String, Integer> regionOrder = new HashMap<>();
        for (int i = 0; i < regions.size(); i++) {
            regionOrder.putIfAbsent(regions.get(i).key(), i);
        }
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed()
                .thenComparing(Candidate::sameName, Comparator.reverseOrder())
                .thenComparingInt(c -> regionOrder.getOrDefault(c.regionKey(), Integer.MAX_VALUE)));
        Map<String, String> byRegion = new HashMap<>();
        Set<String> usedFields = new HashSet<>();
        for (Candidate candidate : ranked) {
            if (candidate.score() < threshold) {
                break;
            }
            if (byRegion.containsKey(candidate.regionKey()) || usedFields.contains(candidate.fieldName())) {
                continue;
            }
            byRegion.put(candidate.regionKey(), candidate.fieldName());
            usedFields.add(candidate.fieldName());
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (TemplateRegion region : regions) {
            String field = byRegion.get(region.key());
            if (field != null) {
                ordered.put(region.key(), field);
            }
        }
        return ordered;
    }

    /** Whether a region of {@code kind} can take a field of {@code dataType}. */
    static boolean compatible(RegionKind kind, String dataType) {
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case IMAGE, SIGNATURE -> false;
            case CHECKBOX, TEXT, MULTILINE -> true;
            case DATE -> isDateLike(dataType) || isText(dataType);
            case NUMBER -> isNumeric(dataType) || isText(dataType);
        };
    }

    static boolean isDateLike(String dataType) {
        String type = lower(dataType);
        return type.contains("date") || type.contains("time");
    }

    static boolean isNumeric(String dataType) {
        String type = lower(dataType);
        return type.contains("number") || type.contains("numeric") || type.contains("int")
                || type.contains("decimal") || type.contains("amount") || type.contains("currency")
                || type.contains("float") || type.contains("double");
    }

    /** Text, or a type the rules do not know (treated as free text). */
    static boolean isText(String dataType) {
        return !isDateLike(dataType) && !isNumeric(dataType);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static double round(double score) {
        return Math.round(score * 1000) / 1000.0;
    }
}
