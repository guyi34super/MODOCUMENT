package com.modocument.infrastructure.settings;

import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingGroup;
import com.modocument.domain.model.settings.SettingScope;
import com.modocument.domain.model.settings.SettingType;
import com.modocument.domain.model.settings.SettingValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings catalogue at runtime (ws20): {@link SettingDefinitions} plus
 * each setting's EFFECTIVE shipped default, read once at startup from the
 * Spring property it names — so an environment override of the yml value
 * (e.g. {@code MODOCUMENT_PASSWORD_MIN_LENGTH}) is the default the settings
 * layer starts from, exactly what the consumer sees today. Settings without
 * a property (or whose property is absent) use the code default.
 *
 * <p>A shipped default outside its hard limits is NOT corrected here: it is
 * the operator's explicit configuration (dev/test profiles legitimately
 * switch e.g. retention off). {@link SettingsStartupCheck} reports it — a
 * WARN, or a refused boot when guardrails are enforced.
 */
@Component
public class SettingsCatalogue {

    private static final Logger log = LoggerFactory.getLogger(SettingsCatalogue.class);

    /** {@code prefix[name]suffix} — a list entry addressed by its {@code name}. */
    private static final Pattern NAMED_ENTRY = Pattern.compile("^(.+?)\\[([^\\]]*\\D[^\\]]*)](.*)$");
    private static final int MAX_LIST_SCAN = 1000;

    private final Map<String, SettingDefinition> byKey;
    private final Map<String, Object> defaults;
    private final Map<String, String> resolvedProperties;
    private final List<DefaultViolation> violations;

    @Autowired
    public SettingsCatalogue(Environment environment) {
        this(SettingDefinitions.ALL, environment);
    }

    SettingsCatalogue(List<SettingDefinition> definitions, PropertyResolver properties) {
        Map<String, SettingDefinition> keyed = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        List<DefaultViolation> outside = new ArrayList<>();
        for (SettingDefinition definition : definitions) {
            if (keyed.put(definition.key(), definition) != null) {
                throw new IllegalStateException("Duplicate setting key " + definition.key());
            }
            String property = definition.propertyName() == null
                    ? null : resolvePropertyName(definition.propertyName(), properties);
            if (property != null) {
                resolved.put(definition.key(), property);
            } else if (definition.propertyName() != null) {
                log.warn("Setting {}: property {} could not be resolved; using the code default",
                        definition.key(), definition.propertyName());
            }
            Object value = readDefault(definition, property, properties);
            values.put(definition.key(), value);
            if (!definition.isWithinHardLimits(value)) {
                outside.add(new DefaultViolation(definition, property, value));
            }
        }
        this.byKey = Collections.unmodifiableMap(keyed);
        this.defaults = Collections.unmodifiableMap(values);
        this.resolvedProperties = Collections.unmodifiableMap(resolved);
        this.violations = List.copyOf(outside);
    }

    /** Every definition, in catalogue (display) order. */
    public List<SettingDefinition> all() {
        return List.copyOf(byKey.values());
    }

    /** The definitions of one scope, in catalogue order. */
    public List<SettingDefinition> ofScope(SettingScope scope) {
        return byKey.values().stream().filter(d -> d.scope() == scope).toList();
    }

    public Optional<SettingDefinition> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
    }

    /** The definition of a key the caller knows exists (a programming error otherwise). */
    public SettingDefinition require(String key) {
        SettingDefinition definition = byKey.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown setting key '" + key + "'.");
        }
        return definition;
    }

    /** The effective shipped default (property value, else code default); typed. */
    public Object defaultValue(SettingDefinition definition) {
        return defaults.get(definition.key());
    }

    /** The concrete property the default was read from (list selectors resolved); may be null. */
    public String resolvedPropertyName(SettingDefinition definition) {
        return resolvedProperties.get(definition.key());
    }

    /** Shipped defaults outside their hard limits (see {@link SettingsStartupCheck}). */
    public List<DefaultViolation> defaultViolations() {
        return violations;
    }

    /** Definitions grouped by tab, in tab and catalogue order. */
    public Map<SettingGroup, List<SettingDefinition>> grouped(List<SettingDefinition> subset) {
        Map<SettingGroup, List<SettingDefinition>> groups = new LinkedHashMap<>();
        for (SettingGroup group : SettingGroup.values()) {
            List<SettingDefinition> members = subset.stream()
                    .filter(d -> d.group() == group).toList();
            if (!members.isEmpty()) {
                groups.put(group, members);
            }
        }
        return groups;
    }

    /**
     * Resolves a {@code prefix[name]suffix} list selector to the index whose
     * {@code name} property matches ({@code modocument.alerts.rules[export-burst].threshold}
     * becomes {@code modocument.alerts.rules[13].threshold}); plain names pass
     * through. Null when the named entry does not exist.
     */
    public static String resolvePropertyName(String propertyName, PropertyResolver properties) {
        Matcher named = NAMED_ENTRY.matcher(propertyName);
        if (!named.matches()) {
            return propertyName;
        }
        String prefix = named.group(1);
        String wanted = named.group(2);
        for (int i = 0; i < MAX_LIST_SCAN; i++) {
            String name = properties.getProperty(prefix + "[" + i + "].name");
            if (name == null) {
                return null;
            }
            if (name.trim().equals(wanted)) {
                return prefix + "[" + i + "]" + named.group(3);
            }
        }
        return null;
    }

    private static Object readDefault(SettingDefinition definition, String property,
                                      PropertyResolver properties) {
        if (property == null) {
            return definition.codeDefault();
        }
        String text;
        try {
            text = properties.getProperty(property);
        } catch (RuntimeException unresolvable) {
            log.warn("Setting {}: property {} could not be read ({}); using the code default",
                    definition.key(), property, unresolvable.getMessage());
            return definition.codeDefault();
        }
        if (text == null || text.isBlank()) {
            return definition.codeDefault();
        }
        try {
            return definition.type() == SettingType.ENUM
                    ? definition.parse(text)
                    : SettingValues.parse(definition.type(), text, definition.propertyUnit());
        } catch (IllegalArgumentException invalid) {
            log.warn("Setting {}: property {} holds an invalid value ({}); using the code default",
                    definition.key(), property, invalid.getMessage());
            return definition.codeDefault();
        }
    }

    /** A shipped default that lies outside its setting's hard limits. */
    public record DefaultViolation(SettingDefinition definition, String propertyName,
                                   Object value) {

        public String describe() {
            String limits = definition.type().isRanged()
                    ? "[" + definition.format(definition.hardMin()) + ", "
                    + definition.format(definition.hardMax()) + "]"
                    : definition.allowedValues().toString();
            return definition.key() + " = " + definition.format(value)
                    + (propertyName == null ? "" : " (from " + propertyName + ")")
                    + " is outside " + limits;
        }
    }
}
