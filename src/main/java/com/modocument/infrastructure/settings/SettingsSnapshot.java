package com.modocument.infrastructure.settings;

import com.modocument.domain.entity.BankSetting;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An immutable, pre-parsed copy of every stored setting (ws20), as of one
 * {@code ConfigRevision}. Built by {@link SettingsCache} on reload and read
 * lock-free by {@link SettingsResolver}: no database access per setting read.
 *
 * <p>Stored text is parsed and checked ONCE here. A row whose value does not
 * parse, is not an allowed value (BOOLEAN/ENUM), names an unknown key, or is
 * a bank row for a PLATFORM-scope setting is skipped with a WARN — the
 * setting then inherits, it never breaks a consumer. Ranged values outside
 * the limits are kept and clamped at resolution (limits may tighten in a
 * newer release; the {@code clamped} flag makes that visible).
 */
public final class SettingsSnapshot {

    private static final Logger log = LoggerFactory.getLogger(SettingsSnapshot.class);

    /** Before the first successful load: no rows, a revision no real one equals. */
    public static final SettingsSnapshot EMPTY = new SettingsSnapshot(-1L, null, Map.of());

    private final long revision;
    private final Integer platformBankId;
    private final Map<Integer, Map<String, StoredSetting>> rowsByBank;

    private SettingsSnapshot(long revision, Integer platformBankId,
                             Map<Integer, Map<String, StoredSetting>> rowsByBank) {
        this.revision = revision;
        this.platformBankId = platformBankId;
        this.rowsByBank = rowsByBank;
    }

    /** Parses and validates raw rows against the catalogue. */
    public static SettingsSnapshot build(long revision, OptionalInt platformBankId,
                                         List<BankSetting> rows, SettingsCatalogue catalogue) {
        Integer platform = platformBankId.isPresent() ? platformBankId.getAsInt() : null;
        Map<Integer, Map<String, StoredSetting>> byBank = new HashMap<>();
        for (BankSetting row : rows) {
            Optional<SettingDefinition> found = catalogue.find(row.getSettingKey());
            if (found.isEmpty()) {
                log.warn("Stored setting {} of bank {} is not in the catalogue; ignored",
                        row.getSettingKey(), row.getBankId());
                continue;
            }
            SettingDefinition definition = found.get();
            boolean platformRow = platform != null && platform == row.getBankId();
            if (!platformRow && definition.scope() == SettingScope.PLATFORM) {
                log.warn("Stored setting {} of bank {} is platform-only; ignored",
                        definition.key(), row.getBankId());
                continue;
            }
            Object value = parseValue(definition, row);
            Object bankMin = null;
            Object bankMax = null;
            if (platformRow && definition.hasBankBounds()) {
                bankMin = parseBound(definition, row, row.getBankMinText(), "bankMin");
                bankMax = parseBound(definition, row, row.getBankMaxText(), "bankMax");
                if (bankMin != null && bankMax != null
                        && SettingValues.compare(definition.type(), bankMin, bankMax) > 0) {
                    log.warn("Platform bounds of setting {} are inverted ({} > {}); ignored",
                            definition.key(), row.getBankMinText(), row.getBankMaxText());
                    bankMin = null;
                    bankMax = null;
                }
            }
            byBank.computeIfAbsent(row.getBankId(), id -> new HashMap<>())
                    .put(definition.key(), new StoredSetting(row.getBankId(), definition.key(),
                            value, bankMin, bankMax, row.getVersion()));
        }
        Map<Integer, Map<String, StoredSetting>> frozen = new HashMap<>();
        byBank.forEach((bank, keyed) -> frozen.put(bank, Map.copyOf(keyed)));
        return new SettingsSnapshot(revision, platform, Collections.unmodifiableMap(frozen));
    }

    /** The ConfigRevision this snapshot reflects (-1 before the first load). */
    public long revision() {
        return revision;
    }

    /** The platform bank as of this snapshot (empty while none exists). */
    public OptionalInt platformBankId() {
        return platformBankId == null ? OptionalInt.empty() : OptionalInt.of(platformBankId);
    }

    public boolean isPlatformBank(int bankId) {
        return platformBankId != null && platformBankId == bankId;
    }

    /** A bank's stored row for a key, or null. */
    public StoredSetting row(int bankId, String key) {
        Map<String, StoredSetting> keyed = rowsByBank.get(bankId);
        return keyed == null ? null : keyed.get(key);
    }

    /** The platform layer's row for a key, or null (also while no platform bank exists). */
    public StoredSetting platformRow(String key) {
        return platformBankId == null ? null : row(platformBankId, key);
    }

    /** Number of stored rows across all banks (diagnostics). */
    public int size() {
        return rowsByBank.values().stream().mapToInt(Map::size).sum();
    }

    private static Object parseValue(SettingDefinition definition, BankSetting row) {
        String text = row.getValueText();
        if (text == null) {
            return null;
        }
        try {
            Object value = definition.parse(text);
            if (!definition.type().isRanged() && !definition.isWithinHardLimits(value)) {
                log.warn("Stored setting {} of bank {} holds a value that is not allowed ({}); "
                        + "skipped", definition.key(), row.getBankId(), abbreviate(text));
                return null;
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            log.warn("Stored setting {} of bank {} holds an invalid value ({}); skipped",
                    definition.key(), row.getBankId(), invalid.getMessage());
            return null;
        }
    }

    private static Object parseBound(SettingDefinition definition, BankSetting row, String text,
                                     String which) {
        if (text == null) {
            return null;
        }
        try {
            return definition.parse(text);
        } catch (IllegalArgumentException invalid) {
            log.warn("Platform {} of setting {} is invalid ({}); ignored", which,
                    definition.key(), invalid.getMessage());
            return null;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /**
     * One parsed BankSetting row: {@code value} null = inherit (or skipped as
     * invalid); bounds only on platform rows of ranged BANK settings.
     */
    public record StoredSetting(int bankId, String key, Object value, Object bankMin,
                                Object bankMax, int version) {
    }
}
