package com.modocument.application.usecase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.modocument.application.dto.AutoMappingPromptInput;
import com.modocument.application.mapping.ColumnNameSimilarity;
import com.modocument.application.mapping.MappingContext;
import com.modocument.application.mapping.MappingProperties;
import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.PromptStrategy;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.entity.ValidationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Tiered source-column → template-field mapper. Deterministic code tiers run
 * first — exact and normalized name matches, alias/token similarity,
 * value-shape scoring through the template's own validation rules, and
 * mappings learned from previous jobs of the same template — combined into a
 * threshold-gated unique assignment. Only the columns the code tiers cannot
 * resolve are (optionally) offered to the LLM, and that prompt carries
 * column/field NAMES only, never data.
 */
@Service
public class AutoMapUseCase {

    private static final Logger log = LoggerFactory.getLogger(AutoMapUseCase.class);

    /** Confidence attached to a majority vote from past corrected mappings. */
    private static final double HISTORY_SCORE = 0.95;
    /** Shape contributes as a booster on top of a name/history signal. */
    private static final double SHAPE_BOOST = 0.15;
    /** Minimum name/history signal for shape to act as a booster. */
    private static final double WEAK_NAME_SIGNAL = 0.3;
    /** Shape alone qualifies only when it is this convincing... */
    private static final double SHAPE_ONLY_MIN = 0.8;
    /** ...over at least this many non-blank samples. */
    private static final int SHAPE_ONLY_MIN_SAMPLES = 5;

    private final List<PromptStrategy> promptStrategies;
    private final AiFilesService aiFilesService;
    private final ValueShapeScorer shapeScorer;
    private final IngestionJobRepository jobs;
    private final MappingProperties properties;

    public AutoMapUseCase(List<PromptStrategy> promptStrategies,
                          AiFilesService aiFilesService,
                          ValueShapeScorer shapeScorer,
                          IngestionJobRepository jobs,
                          MappingProperties properties) {
        this.promptStrategies = promptStrategies;
        this.aiFilesService = aiFilesService;
        this.shapeScorer = shapeScorer;
        this.jobs = jobs;
        this.properties = properties;
    }

    public Map<String, String> map(MappingContext context) {
        List<String> fieldNames = context.fields().stream().map(TemplateFields::getFieldName).toList();
        Map<String, TemplateFields> fieldsByName = new LinkedHashMap<>();
        context.fields().forEach(f -> fieldsByName.putIfAbsent(f.getFieldName(), f));

        Map<String, String> mapping = new LinkedHashMap<>();
        Set<String> pool = new LinkedHashSet<>(fieldNames);

        // Tiers 1-2: exact then normalized name match (each field used once).
        Map<String, String> normalizedFields = new LinkedHashMap<>();
        for (String field : fieldNames) {
            normalizedFields.putIfAbsent(normalize(field), field);
        }
        // Exact matches claim their field before normalized ones, so a column
        // named exactly like a field never loses it to a fuzzier neighbour.
        List<String> unresolved = new ArrayList<>();
        for (String column : context.columns()) {
            if (!mapping.containsKey(column) && pool.contains(column)) {
                mapping.put(column, column);
                pool.remove(column);
            }
        }
        for (String column : context.columns()) {
            if (mapping.containsKey(column)) {
                continue;
            }
            String byNormalizedName = normalizedFields.get(normalize(column));
            if (byNormalizedName != null && pool.contains(byNormalizedName)) {
                mapping.put(column, byNormalizedName);
                pool.remove(byNormalizedName);
            } else {
                unresolved.add(column);
            }
        }

        // Tiers 3-6: scored candidates + threshold-gated unique assignment.
        if (!unresolved.isEmpty() && !pool.isEmpty()) {
            unresolved = assignScored(context, unresolved, pool, fieldsByName, mapping);
        }

        // Tier 7: the LLM sees only what code could not resolve — names only.
        if (!unresolved.isEmpty() && !pool.isEmpty() && properties.isLlmFallbackEnabled()) {
            unresolved = mapWithLlm(unresolved, pool, mapping);
        }

        // Unresolved columns keep explicit null entries (existing contract:
        // serialized into MappingJson; applyMapping skips them). putIfAbsent
        // because a repeated column name must not erase its own assignment.
        unresolved.forEach(column -> mapping.putIfAbsent(column, null));
        return mapping;
    }

    private record Candidate(String column, String field, double score, String tier) {
    }

    /** Returns the columns that remain unresolved after code-tier assignment. */
    private List<String> assignScored(MappingContext context, List<String> unresolved,
                                      Set<String> pool, Map<String, TemplateFields> fieldsByName,
                                      Map<String, String> mapping) {
        Map<String, String> historyVotes = historyVotes(context.templateId());
        Map<String, List<ValidationRule>> rulesByField = shapeScorer.rulesByField(context.templateId());

        List<Candidate> candidates = new ArrayList<>();
        for (String column : unresolved) {
            List<String> samples = samplesFor(column, context.sampleRows());
            String votedField = historyVotes.get(normalize(column));
            for (String field : pool) {
                double history = field.equals(votedField) ? HISTORY_SCORE : 0;
                double name = ColumnNameSimilarity.score(column, field, properties.getAliases());
                double base = Math.max(history, name);
                OptionalDouble shape = shapeScorer.score(
                        fieldsByName.get(field), rulesByField.get(field), samples);

                double score;
                String tier;
                if (base >= WEAK_NAME_SIGNAL) {
                    score = Math.min(0.99,
                            base + (shape.isPresent() ? SHAPE_BOOST * shape.getAsDouble() : 0));
                    tier = history >= name ? "history" : "similarity";
                } else if (shape.isPresent() && shape.getAsDouble() >= SHAPE_ONLY_MIN
                        && samples.size() >= SHAPE_ONLY_MIN_SAMPLES) {
                    score = 0.85 * shape.getAsDouble();
                    tier = "value-shape";
                } else {
                    continue;
                }
                candidates.add(new Candidate(column, field, score, tier));
            }
        }

        candidates.sort((a, b) -> Double.compare(b.score(), a.score()));
        Set<String> assignedColumns = new LinkedHashSet<>();
        for (Candidate candidate : candidates) {
            if (candidate.score() < properties.getConfidenceThreshold()
                    || assignedColumns.contains(candidate.column())
                    || !pool.contains(candidate.field())) {
                continue;
            }
            mapping.put(candidate.column(), candidate.field());
            assignedColumns.add(candidate.column());
            pool.remove(candidate.field());
            log.debug("Auto-map: '{}' -> '{}' via {} tier (score {})",
                    candidate.column(), candidate.field(), candidate.tier(),
                    String.format(Locale.ROOT, "%.2f", candidate.score()));
        }
        return unresolved.stream().filter(c -> !assignedColumns.contains(c)).toList();
    }

    /** Majority-vote target per normalized column name from recent completed jobs. */
    private Map<String, String> historyVotes(int templateId) {
        List<String> mappingJsons;
        try {
            mappingJsons = jobs.listRecentMappingJson(templateId, properties.getHistoryJobs());
        } catch (Exception e) {
            log.warn("Could not load mapping history for template {}: {}", templateId, e.getMessage());
            return Map.of();
        }
        Map<String, Map<String, Integer>> votes = new HashMap<>();
        for (String json : mappingJsons) {
            Map<String, String> past;
            try {
                past = IngestionJobUseCase.fromJson(json, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception e) {
                continue;
            }
            for (Map.Entry<String, String> entry : past.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                votes.computeIfAbsent(normalize(entry.getKey()), k -> new HashMap<>())
                        .merge(entry.getValue(), 1, Integer::sum);
            }
        }
        Map<String, String> majority = new HashMap<>();
        // Tie-break by field name so identical history yields identical
        // mappings run to run.
        votes.forEach((column, fieldVotes) -> fieldVotes.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder())))
                .ifPresent(winner -> majority.put(column, winner.getKey())));
        return majority;
    }

    private List<String> samplesFor(String column, List<Map<String, String>> rows) {
        List<String> samples = new ArrayList<>();
        if (rows == null) {
            return samples;
        }
        for (Map<String, String> row : rows) {
            String value = row.get(column);
            if (value != null && !value.isBlank()) {
                samples.add(value);
                if (samples.size() >= properties.getSampleSize()) {
                    break;
                }
            }
        }
        return samples;
    }

    /**
     * Returns the columns that remain unresolved. A failing LLM never fails
     * the mapping stage — the optional helper degrades to "left unmapped".
     */
    private List<String> mapWithLlm(List<String> columns, Set<String> pool,
                                    Map<String, String> mapping) {
        List<String> offeredFields = List.copyOf(pool);
        Map<String, String> llmMapping;
        try {
            PromptStrategy strategy = promptStrategies.stream()
                    .filter(s -> s.getType().equals("AutoMapping"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("AutoMapping prompt not registered."));
            String prompt = strategy.buildPrompt(new AutoMappingPromptInput(
                    String.join(", ", columns), String.join(", ", offeredFields)));
            llmMapping = aiFilesService.sendForObject(
                    prompt, new ParameterizedTypeReference<Map<String, String>>() {
                    });
        } catch (Exception e) {
            log.warn("LLM auto-mapping skipped ({}); leaving {} column(s) unmapped",
                    e.getMessage(), columns.size());
            return columns;
        }

        // Trust only mappings onto offered (still-unassigned) template fields.
        List<String> stillUnresolved = new ArrayList<>();
        for (String column : columns) {
            String target = llmMapping == null ? null : llmMapping.get(column);
            if (target != null && pool.contains(target)) {
                mapping.put(column, target);
                pool.remove(target);
                log.debug("Auto-map: '{}' -> '{}' via llm tier", column, target);
            } else {
                stillUnresolved.add(column);
            }
        }
        return stillUnresolved;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
