package com.modocument.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.ParseProvenance;
import com.modocument.application.dto.ParseResult;
import com.modocument.application.dto.TableData;
import com.modocument.application.port.SettingsReader;
import com.modocument.domain.entity.AiResultCacheEntry;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.Hashing;
import com.modocument.domain.model.ParsedBy;
import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.infrastructure.persistence.AiResultCacheJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AI result cache (ws28, V286): re-parsing a document whose text,
 * template version, extraction mode/options, model and prompt version are
 * unchanged reuses the stored AI result instead of paying for the tokens
 * again. Bank-scoped (RLS on BankId), switched by the BANK setting
 * {@code ai.resultCache.enabled}, purged with the customer-data retention.
 *
 * <p>Only a pure AI result is stored (not AI_PARTIAL — a batch the code
 * repaired after an AI failure must be retried by the AI next time). A hit
 * is counted on the row ({@code HitCount}) and as the metric
 * {@code modocument.ai.result_cache{result=hit|miss|store}} — never audited.
 * Every failure of the cache itself is swallowed: it can only ever save an
 * AI call, never fail a parse.
 */
@Component
public class AiResultCache {

    private static final Logger log = LoggerFactory.getLogger(AiResultCache.class);
    static final String METRIC = "modocument.ai.result_cache";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiResultCacheJpaRepository repository;
    private final SettingsReader settings;
    private final ObjectProvider<MeterRegistry> meters;
    private final String model;
    private final Clock clock = Clock.systemUTC();

    public AiResultCache(AiResultCacheJpaRepository repository,
                         SettingsReader settings,
                         ObjectProvider<MeterRegistry> meters,
                         @Value("${spring.ai.openai.chat.options.model:}") String model) {
        this.repository = repository;
        this.settings = settings;
        this.meters = meters;
        this.model = model == null || model.isBlank() ? "default" : model;
    }

    /** The model a parse runs on — part of the key. */
    public String model() {
        return model;
    }

    /**
     * The cache key: sha256 over sha256(text), the template id and a hash of
     * its field definitions, the mode/options string, the model and the
     * prompt version.
     */
    public String key(String documentText, int templateId, List<TemplateFields> fields,
                      String mode, String promptVersion) {
        return Hashing.sha256(String.join("\n",
                "text=" + Hashing.sha256(documentText == null ? "" : documentText),
                "template=" + templateId,
                "templateVersion=" + templateVersion(fields),
                "mode=" + mode,
                "model=" + model,
                "prompt=" + promptVersion));
    }

    /**
     * The template "version": a hash of everything about its fields that
     * reaches the prompt or the mapping (names, types, extraction hints).
     */
    static String templateVersion(List<TemplateFields> fields) {
        if (fields == null || fields.isEmpty()) {
            return "none";
        }
        StringBuilder canonical = new StringBuilder();
        fields.stream()
                .sorted(Comparator.comparingInt(TemplateFields::getTemplateFieldsId)
                        .thenComparing(f -> String.valueOf(f.getFieldName())))
                .forEach(field -> canonical.append(field.getTemplateFieldsId()).append('|')
                        .append(field.getFieldName()).append('|')
                        .append(field.getTemplateDataTypeId()).append('|')
                        .append(field.getAliases()).append('|')
                        .append(field.getLabelPattern()).append('|')
                        .append(field.getValuePattern()).append('|')
                        .append(field.getDateFormats()).append('\n'));
        return Hashing.sha256(canonical.toString());
    }

    /** Whether the bank uses the cache (default true; unreadable setting = on). */
    public boolean enabled(Integer bankId) {
        if (bankId == null) {
            return false;
        }
        try {
            return settings.getBool(SettingKeys.AI_RESULT_CACHE_ENABLED, bankId);
        } catch (RuntimeException e) {
            log.debug("ai.resultCache.enabled unreadable for bank {}: {}", bankId,
                    e.getClass().getSimpleName());
            return true;
        }
    }

    /** The cached result for {@code key} in the bank, counted as a hit or a miss. */
    public Optional<ParseResult> lookup(int bankId, String key) {
        try {
            Optional<AiResultCacheEntry> entry = repository.findByBankIdAndCacheKey(bankId, key);
            if (entry.isEmpty()) {
                count("miss");
                return Optional.empty();
            }
            ParseResult result = decode(entry.get().getResultJson());
            repository.incrementHits(entry.get().getId());
            count("hit");
            return Optional.of(result);
        } catch (RuntimeException e) {
            log.warn("AI result cache lookup failed ({}); calling the AI", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Stores a successful, pure-AI parse result. Never throws. */
    public void store(int bankId, String key, String promptVersion, ParseResult result) {
        if (result == null || result.table() == null || result.table().isEmpty()
                || result.provenance() == null || result.provenance().parsedBy() != ParsedBy.AI) {
            return;
        }
        try {
            AiResultCacheEntry entry = new AiResultCacheEntry();
            entry.setBankId(bankId);
            entry.setCacheKey(key);
            entry.setPromptVersion(promptVersion);
            entry.setModel(model.length() <= 200 ? model : model.substring(0, 200));
            entry.setResultJson(encode(result));
            entry.setCreatedAt(Instant.now(clock));
            repository.save(entry);
            count("store");
        } catch (DataIntegrityViolationException raced) {
            log.debug("AI result cache: key already stored by a concurrent parse");
        } catch (RuntimeException e) {
            log.warn("AI result cache store failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String encode(ParseResult result) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("table", result.table());
        json.put("mapping", result.suggestedMapping());
        json.put("strategy", result.provenance().strategy());
        try {
            return MAPPER.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode the AI result", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ParseResult decode(String json) {
        try {
            Map<String, Object> node = MAPPER.readValue(json, Map.class);
            TableData table = MAPPER.convertValue(node.get("table"), TableData.class);
            Map<String, String> mapping = node.get("mapping") == null ? Map.of()
                    : MAPPER.convertValue(node.get("mapping"), Map.class);
            String strategy = node.get("strategy") == null ? ParseProvenance.LLM_SINGLE_PASS
                    : String.valueOf(node.get("strategy"));
            return new ParseResult(table, mapping, ParseProvenance.ai(strategy));
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable AI result cache entry", e);
        }
    }

    private void count(String result) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            registry.counter(METRIC, "result", result).increment();
        }
    }
}
