package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.dto.SettingsUpdateRequest;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PendingChangeRepository;
import com.modocument.domain.entity.BankSetting;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.PendingChangeStatus;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingException;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingTier;
import com.modocument.domain.model.settings.SettingType;
import com.modocument.domain.model.settings.SettingValues;
import com.modocument.infrastructure.settings.SettingsCatalogue;
import com.modocument.infrastructure.settings.SettingsResolver;
import com.modocument.infrastructure.settings.SettingsStore;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The validation and write rules of settings changes (ws20), shared by
 * {@link SettingsUseCase} (the PUT endpoints) and the two maker/checker
 * appliers — so a change is judged by exactly the same rules when it is
 * typed, when it is submitted, and again when it is approved.
 *
 * <p>Refusals carry the contract's codes ({@link SettingException.Code}):
 * unknown key or wrong layer → SETTING_UNKNOWN, a locked setting →
 * SETTING_LOCKED, outside the effective range / not an allowed value →
 * SETTING_OUT_OF_RANGE, a stale version → SETTING_VERSION_CONFLICT, a key
 * already in an undecided change → SETTING_CHANGE_PENDING. A value of the
 * wrong JSON type is a plain 400.
 *
 * <p>Everything here runs inside the caller's transaction and tenant.
 * Effective limits come from the settings snapshot (the platform layer's
 * bank bounds live in the platform bank's rows, which RLS hides from a
 * bank's own transaction); callers refresh the snapshot first. Row
 * versions and "from" values are read from the database.
 */
@Component
public class SettingChangeService {

    /** Which layer a change writes. */
    public enum Layer {
        /** A customer bank's own overrides (/api/admin/settings, SETTING_UPDATE). */
        BANK(ChangeType.SETTING_UPDATE, "SECURITY_SETTING_UPDATED"),
        /** The platform bank's rows: defaults + bank bounds (PLATFORM_SETTING_UPDATE). */
        PLATFORM(ChangeType.PLATFORM_SETTING_UPDATE, "PLATFORM_SETTING_UPDATED");

        private final ChangeType changeType;
        private final String approvedAuditAction;

        Layer(ChangeType changeType, String approvedAuditAction) {
            this.changeType = changeType;
            this.approvedAuditAction = approvedAuditAction;
        }

        public ChangeType changeType() {
            return changeType;
        }

        /** Audit action of an approved (maker/checker) change at this layer. */
        public String approvedAuditAction() {
            return approvedAuditAction;
        }
    }

    /** Audit action of a direct (operational) change at either layer. */
    public static final String DIRECT_AUDIT_ACTION = "SETTING_UPDATED";
    static final String AUDIT_ENTITY_TYPE = "Setting";

    private final SettingsCatalogue catalogue;
    private final SettingsResolver resolver;
    private final SettingsStore store;
    private final PendingChangeRepository pendingChanges;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public SettingChangeService(SettingsCatalogue catalogue, SettingsResolver resolver,
                                SettingsStore store, PendingChangeRepository pendingChanges,
                                AuditRecorder audit, ObjectMapper objectMapper) {
        this.catalogue = catalogue;
        this.resolver = resolver;
        this.store = store;
        this.pendingChanges = pendingChanges;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ validation

    /** The PUT body's changes in the common form. */
    public static List<RawChange> fromRequest(SettingsUpdateRequest request) {
        List<RawChange> raw = new ArrayList<>();
        for (SettingsUpdateRequest.Change change : request.changes()) {
            raw.add(new RawChange(change.key(), change.value(), change.bankMin(),
                    change.bankMax(), change.expectedVersion()));
        }
        return raw;
    }

    /**
     * Judges every change against the CURRENT state; nothing is written.
     *
     * @param ignorePendingChangeId the change being approved (its own key is
     *     of course "pending"); null at submit/PUT time
     * @param checkVersions compare {@code expectedVersion} with the stored
     *     row now (PUT/submit) — at approval the write itself does it
     */
    public List<ParsedChange> validate(Layer layer, int bankId, List<RawChange> raw,
                                       Integer ignorePendingChangeId, boolean checkVersions) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("At least one change is required.");
        }
        Map<String, Integer> pending = openChangeKeys(layer, bankId);
        if (ignorePendingChangeId != null) {
            pending.values().removeIf(id -> id.equals(ignorePendingChangeId));
        }
        Set<String> seen = new HashSet<>();
        List<ParsedChange> parsed = new ArrayList<>();
        for (RawChange change : raw) {
            String key = change.key() == null ? "" : change.key().trim();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Setting " + key
                        + " appears more than once in the request.");
            }
            SettingDefinition definition = requireDefinition(layer, key);
            if (definition.locked()) {
                throw new SettingException(SettingException.Code.SETTING_LOCKED, key,
                        "Setting " + key + " is locked and cannot be changed.");
            }
            if (pending.containsKey(key)) {
                throw new SettingException(SettingException.Code.SETTING_CHANGE_PENDING, key,
                        "Setting " + key + " already has a change awaiting approval (#"
                                + pending.get(key) + ").");
            }
            ParsedChange result = layer == Layer.BANK
                    ? parseBankChange(definition, bankId, change)
                    : parsePlatformChange(definition, bankId, change);
            if (checkVersions && result.currentVersion() != result.baseVersion()) {
                throw new SettingException(SettingException.Code.SETTING_VERSION_CONFLICT, key,
                        "Setting " + key + " was changed by someone else (version "
                                + result.baseVersion() + " expected, " + result.currentVersion()
                                + " found). Reload the settings and try again.");
            }
            parsed.add(result);
        }
        return parsed;
    }

    private SettingDefinition requireDefinition(Layer layer, String key) {
        Optional<SettingDefinition> found = catalogue.find(key);
        if (found.isEmpty()) {
            throw new SettingException(SettingException.Code.SETTING_UNKNOWN, key,
                    "Unknown setting '" + abbreviate(key) + "'.");
        }
        if (layer == Layer.BANK && found.get().scope() != SettingScope.BANK) {
            throw new SettingException(SettingException.Code.SETTING_UNKNOWN, key,
                    "Setting " + key + " is managed by the platform owner, not per bank.");
        }
        return found.get();
    }

    private ParsedChange parseBankChange(SettingDefinition definition, int bankId,
                                         RawChange change) {
        if (isPresent(change.bankMin()) || isPresent(change.bankMax())) {
            throw new IllegalArgumentException("Bank bounds of " + definition.key()
                    + " are set by the platform owner.");
        }
        Object value = fromJson(definition, change.value());
        if (value != null) {
            requireInBankRange(definition, bankId, value);
        }
        Optional<BankSetting> row = store.find(bankId, definition.key());
        return new ParsedChange(definition, true, value, false, null, false, null,
                versionOf(change), row.map(BankSetting::getVersion).orElse(0),
                storedValue(definition, row.map(BankSetting::getValueText).orElse(null)),
                null, null, definition.tier() == SettingTier.SECURITY);
    }

    private ParsedChange parsePlatformChange(SettingDefinition definition, int bankId,
                                             RawChange change) {
        boolean setValue = change.value() != null;
        boolean setMin = change.bankMin() != null;
        boolean setMax = change.bankMax() != null;
        if (!setValue && !setMin && !setMax) {
            throw new IllegalArgumentException("The change of " + definition.key()
                    + " carries no value and no bounds.");
        }
        if ((setMin || setMax) && !definition.hasBankBounds()) {
            throw new IllegalArgumentException("Setting " + definition.key()
                    + " has no bank bounds.");
        }
        Optional<BankSetting> row = store.find(bankId, definition.key());
        Object currentValue = storedValue(definition,
                row.map(BankSetting::getValueText).orElse(null));
        Object currentMin = storedValue(definition,
                row.map(BankSetting::getBankMinText).orElse(null));
        Object currentMax = storedValue(definition,
                row.map(BankSetting::getBankMaxText).orElse(null));

        Object value = setValue ? fromJson(definition, change.value()) : currentValue;
        Object bankMin = setMin ? fromJson(definition, change.bankMin()) : currentMin;
        Object bankMax = setMax ? fromJson(definition, change.bankMax()) : currentMax;
        if (setValue && value != null && !definition.isWithinHardLimits(value)) {
            throw outOfRange(definition, value, definition.hardMin(), definition.hardMax());
        }
        for (Object bound : new Object[] {setMin ? bankMin : null, setMax ? bankMax : null}) {
            if (bound != null && !definition.isWithinHardLimits(bound)) {
                throw outOfRange(definition, bound, definition.hardMin(), definition.hardMax());
            }
        }
        SettingType type = definition.type();
        if (bankMin != null && bankMax != null && SettingValues.compare(type, bankMin, bankMax) > 0) {
            throw new SettingException(SettingException.Code.SETTING_OUT_OF_RANGE,
                    definition.key(), "The bank minimum of " + definition.key()
                    + " cannot exceed its bank maximum.");
        }
        if (value != null && type.isRanged()) {
            // The platform value is the default every bank inherits: it must
            // itself be a value banks are allowed to choose (checked again
            // when only the bounds move).
            Object lo = SettingValues.maxOf(type, definition.hardMin(), bankMin);
            Object hi = SettingValues.minOf(type, definition.hardMax(), bankMax);
            if (SettingValues.compare(type, value, lo) < 0
                    || SettingValues.compare(type, value, hi) > 0) {
                throw outOfRange(definition, value, lo, hi);
            }
        }
        boolean approval = definition.tier() == SettingTier.SECURITY || setMin || setMax;
        return new ParsedChange(definition, setValue, value, setMin, bankMin, setMax, bankMax,
                versionOf(change), row.map(BankSetting::getVersion).orElse(0), currentValue,
                currentMin, currentMax, approval);
    }

    private void requireInBankRange(SettingDefinition definition, int bankId, Object value) {
        if (!definition.type().isRanged()) {
            if (!definition.isWithinHardLimits(value)) {
                throw new SettingException(SettingException.Code.SETTING_OUT_OF_RANGE,
                        definition.key(), "Setting " + definition.key() + " must be one of "
                        + definition.allowedValues() + ".");
            }
            return;
        }
        SettingsResolver.Resolution effective = resolver.resolve(definition, bankId);
        SettingType type = definition.type();
        if (SettingValues.compare(type, value, effective.min()) < 0
                || SettingValues.compare(type, value, effective.max()) > 0) {
            throw outOfRange(definition, value, effective.min(), effective.max());
        }
    }

    // ------------------------------------------------------------ writing

    /**
     * Writes changes and audits each key; the caller bumps the revision once
     * ({@link #bumpRevision()}) after its last write.
     *
     * @param auditAction SETTING_UPDATED (direct) or the layer's approved action
     * @param authorUserId recorded as the row's UpdatedByUserId (the maker)
     */
    public void write(Layer layer, int bankId, List<ParsedChange> changes, String auditAction,
                      Integer authorUserId) {
        for (ParsedChange change : changes) {
            SettingDefinition definition = change.definition();
            SettingsStore.RowChange rowChange = new SettingsStore.RowChange(
                    change.setValue(), text(definition, change.value()),
                    change.setBankMin(), text(definition, change.bankMin()),
                    change.setBankMax(), text(definition, change.bankMax()));
            store.write(bankId, definition.key(), change.baseVersion(), rowChange, authorUserId);
            audit.success(auditAction, AUDIT_ENTITY_TYPE, definition.key(),
                    snapshot(layer, change, true), snapshot(layer, change, false));
        }
    }

    /** One ConfigRevision bump (+ local reload after commit) per transaction. */
    public void bumpRevision() {
        store.bumpRevision();
    }

    // ------------------------------------------------------------ payloads

    /**
     * The maker/checker payload of docs/phase2/contracts.md §2:
     * {@code {changes:[{key, from, to, baseVersion}]}}, plus
     * {@code bankMin}/{@code bankMax} (only when changed) at the platform layer.
     * Values are JSON-typed, null = inherit / no bound.
     */
    public ObjectNode toPayload(Layer layer, List<ParsedChange> changes) {
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode items = payload.putArray("changes");
        for (ParsedChange change : changes) {
            SettingDefinition definition = change.definition();
            ObjectNode item = items.addObject();
            item.put("key", definition.key());
            item.set("from", jsonNode(definition, change.from()));
            item.set("to", jsonNode(definition, change.value()));
            if (layer == Layer.PLATFORM) {
                if (change.setBankMin()) {
                    item.set("bankMin", jsonNode(definition, change.bankMin()));
                }
                if (change.setBankMax()) {
                    item.set("bankMax", jsonNode(definition, change.bankMax()));
                }
            }
            item.put("baseVersion", change.baseVersion());
        }
        return payload;
    }

    /** A persisted payload back in the common form ({@code to} → value). */
    public static List<RawChange> fromPayload(JsonNode payload) {
        JsonNode items = payload == null ? null : payload.get("changes");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new IllegalArgumentException("The payload must carry a non-empty 'changes' list.");
        }
        if (items.size() > 100) {
            throw new IllegalArgumentException("At most 100 changes per request.");
        }
        List<RawChange> raw = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("Each change must be an object.");
            }
            JsonNode key = item.get("key");
            if (key == null || !key.isTextual() || key.asText().length() > 100) {
                throw new IllegalArgumentException("Each change needs a setting 'key'.");
            }
            JsonNode version = item.get("baseVersion");
            if (version == null || !version.canConvertToInt() || version.asInt() < 0) {
                throw new IllegalArgumentException("Each change needs a 'baseVersion' >= 0.");
            }
            JsonNode to = item.get("to");
            raw.add(new RawChange(key.asText(), to == null ? null : to, item.get("bankMin"),
                    item.get("bankMax"), version.asInt()));
        }
        return raw;
    }

    /**
     * Keys covered by this layer's undecided changes in {@code bankId}
     * (key → pending change id).
     */
    public Map<String, Integer> openChangeKeys(Layer layer, int bankId) {
        Map<String, Integer> keys = new HashMap<>();
        for (PendingChange change : pendingChanges.listByBankAndStatus(bankId,
                PendingChangeStatus.REQUESTED)) {
            if (change.getChangeType() != layer.changeType() || change.getBankId() != bankId) {
                continue;
            }
            JsonNode items = ChangePayloads.parse(objectMapper, change.getPayloadJson())
                    .get("changes");
            if (items == null || !items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                JsonNode key = item.get("key");
                if (key != null && key.isTextual()) {
                    keys.putIfAbsent(key.asText(), change.getPendingChangeId());
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------ JSON values

    /**
     * A JSON-typed value of a setting: Java null / JSON null → null
     * (inherit); numbers, booleans and strings by type.
     */
    public static Object fromJson(SettingDefinition definition, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String key = definition.key();
        try {
            return switch (definition.type()) {
                case INT -> {
                    if (node.isIntegralNumber() && node.canConvertToInt()) {
                        yield node.intValue();
                    }
                    if (node.isTextual()) {
                        yield definition.parse(node.asText());
                    }
                    throw new IllegalArgumentException("must be a whole number");
                }
                case DECIMAL -> {
                    if (node.isNumber()) {
                        yield SettingValues.parse(SettingType.DECIMAL,
                                node.decimalValue().toPlainString());
                    }
                    if (node.isTextual()) {
                        yield definition.parse(node.asText());
                    }
                    throw new IllegalArgumentException("must be a number");
                }
                case BOOLEAN -> {
                    if (node.isBoolean()) {
                        yield node.booleanValue();
                    }
                    if (node.isTextual()) {
                        yield definition.parse(node.asText());
                    }
                    throw new IllegalArgumentException("must be true or false");
                }
                case ENUM -> {
                    if (!node.isTextual()) {
                        throw new IllegalArgumentException("must be one of "
                                + definition.allowedValues());
                    }
                    Object value = definition.parse(node.asText());
                    if (!definition.allowedValues().contains((String) value)) {
                        throw new SettingException(SettingException.Code.SETTING_OUT_OF_RANGE,
                                key, "Setting " + key + " must be one of "
                                + definition.allowedValues() + ".");
                    }
                    yield value;
                }
                case DURATION -> {
                    if (!node.isTextual()) {
                        throw new IllegalArgumentException(
                                "must be a duration such as \"30s\", \"15m\" or \"12h\"");
                    }
                    yield definition.parse(node.asText());
                }
            };
        } catch (SettingException refused) {
            throw refused;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Setting " + key + ": " + lowerFirst(
                    invalid.getMessage()));
        }
    }

    /** A typed value as the API shows it (durations as canonical text). */
    public static Object toJson(SettingDefinition definition, Object value) {
        if (value == null) {
            return null;
        }
        return switch (definition.type()) {
            case DURATION -> definition.format(value);
            case DECIMAL -> new BigDecimal(definition.format(value));
            default -> value;
        };
    }

    private JsonNode jsonNode(SettingDefinition definition, Object value) {
        return objectMapper.valueToTree(toJson(definition, value));
    }

    // ------------------------------------------------------------ helpers

    private static boolean isPresent(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static int versionOf(RawChange change) {
        if (change.expectedVersion() == null || change.expectedVersion() < 0) {
            throw new IllegalArgumentException("expectedVersion is required for " + change.key()
                    + " (0 for a setting never changed).");
        }
        return change.expectedVersion();
    }

    private static Object storedValue(SettingDefinition definition, String text) {
        if (text == null) {
            return null;
        }
        try {
            return definition.parse(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String text(SettingDefinition definition, Object value) {
        return value == null ? null : definition.format(value);
    }

    private static SettingException outOfRange(SettingDefinition definition, Object value,
                                               Object lo, Object hi) {
        return new SettingException(SettingException.Code.SETTING_OUT_OF_RANGE, definition.key(),
                "Setting " + definition.key() + " must be between " + definition.format(lo)
                        + " and " + definition.format(hi) + " (got "
                        + definition.format(value) + ").");
    }

    /** The audited before/after of one key (null-safe; values JSON-typed). */
    private static Map<String, Object> snapshot(Layer layer, ParsedChange change, boolean before) {
        SettingDefinition definition = change.definition();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("layer", layer.name());
        values.put("value", toJson(definition, before ? change.from()
                : change.setValue() ? change.value() : change.from()));
        if (change.setBankMin()) {
            values.put("bankMin", toJson(definition, before ? change.fromBankMin()
                    : change.bankMin()));
        }
        if (change.setBankMax()) {
            values.put("bankMax", toJson(definition, before ? change.fromBankMax()
                    : change.bankMax()));
        }
        return values;
    }

    private static String lowerFirst(String text) {
        if (text == null || text.isEmpty()) {
            return "is invalid";
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static String abbreviate(String text) {
        return text.length() <= 64 ? text : text.substring(0, 64) + "…";
    }

    /**
     * A change in the common form, from the PUT body or a stored payload.
     * {@code value}: Java null = absent, JSON null = inherit.
     */
    public record RawChange(String key, JsonNode value, JsonNode bankMin, JsonNode bankMax,
                            Integer expectedVersion) {
    }

    /**
     * A validated change: what to write ({@code set*} flags + typed values),
     * the version it was based on and the current one, the stored values it
     * replaces, and whether it needs a second person's approval.
     */
    public record ParsedChange(SettingDefinition definition, boolean setValue, Object value,
                               boolean setBankMin, Object bankMin, boolean setBankMax,
                               Object bankMax, int baseVersion, int currentVersion, Object from,
                               Object fromBankMin, Object fromBankMax,
                               boolean requiresApproval) {
    }
}
