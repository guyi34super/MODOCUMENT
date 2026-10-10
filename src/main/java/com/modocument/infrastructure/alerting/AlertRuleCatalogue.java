package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingSource;
import com.modocument.domain.model.settings.SettingsChangedEvent;
import com.modocument.infrastructure.settings.SettingsCatalogue;
import com.modocument.infrastructure.settings.SettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The validated, immutable rule catalogue (ws18, SEC-032): built once at
 * startup from {@code modocument.alerts.rules} and FAIL-FAST on a broken
 * entry — a typo in an alert rule must stop the deployment, not silently
 * leave a detection gap. Logs the effective catalogue (one INFO line per
 * rule) as startup evidence of the thresholds in force.
 *
 * <p>Constraints: unique non-blank names (they key the dedupe and appear in
 * every alert), a trigger, at least one action ({@code "*"} = any), a
 * window between 1 minute and 24 hours, and threshold >= 1.
 *
 * <p><b>Platform tuning (ws20).</b> The platform owner may change a
 * windowed rule's threshold and window from the settings page
 * ({@code alerts.<rule>.threshold|window}, PLATFORM scope, maker/checker).
 * Those settings name their rule in their property
 * ({@code modocument.alerts.rules[<rule>].threshold}); a STORED value
 * overlays the yml one — with nothing stored every rule is exactly as
 * configured. The effective list is an immutable snapshot behind an
 * {@link AtomicReference}, rebuilt on every {@link SettingsChangedEvent}
 * (a settings write here, or another task's write picked up by the poll);
 * readers never lock. A stored value is always inside the setting's hard
 * limits (threshold 1-4x the default, window 1-60 min), so an overlay can
 * never break the rule constraints above.
 */
@Component
public class AlertRuleCatalogue {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleCatalogue.class);

    static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    static final Duration MAX_WINDOW = Duration.ofHours(24);

    /** {@code modocument.alerts.rules[<rule>].threshold|window}: an alert-tuning setting. */
    private static final Pattern TUNING_PROPERTY =
            Pattern.compile("^modocument\\.alerts\\.rules\\[([^\\]]+)]\\.(threshold|window)$");

    /** The validated yml catalogue, before any overlay. */
    private final List<AlertRule> configured;
    /** Rule name -> its tuning settings (empty without settings wiring). */
    private final Map<String, Tuning> tuning;
    /** Null = no overlays (unit-test wiring). */
    private final SettingsResolver settings;
    private final AtomicReference<List<AlertRule>> rules = new AtomicReference<>();

    public AlertRuleCatalogue(AlertProperties properties) {
        this(properties, null, null);
    }

    @Autowired
    public AlertRuleCatalogue(AlertProperties properties, SettingsCatalogue settingsCatalogue,
                              SettingsResolver settings) {
        this.configured = build(properties.getRules());
        this.tuning = settingsCatalogue == null ? Map.of() : tuningByRule(settingsCatalogue);
        this.settings = settings;
        List<AlertRule> rules = overlay(configured);
        this.rules.set(rules);
        if (rules.isEmpty()) {
            log.warn("Alert rule catalogue is EMPTY (modocument.alerts.rules) - no security "
                    + "alert will ever be raised. See docs/security/alerting-and-export-caps.md.");
        }
        for (AlertRule rule : rules) {
            log.info("Alert rule {}: trigger={} actions={} outcomes={} roles={} groupBy={} "
                            + "threshold={} window={} severity={}",
                    rule.name(), rule.trigger(),
                    rule.matchesAnyAction() ? "*" : rule.actions(),
                    rule.outcomes().isEmpty() ? "*" : rule.outcomes(),
                    rule.actorRoles().isEmpty() ? "*" : rule.actorRoles(),
                    rule.groupBy(), rule.threshold(), rule.window(), rule.severity());
        }
    }

    /** The effective rules (yml + stored platform tuning), as of the last rebuild. */
    public List<AlertRule> all() {
        return rules.get();
    }

    /** Rebuilds the overlays when the effective settings may have changed. */
    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        rebuild();
    }

    /**
     * Re-applies the stored platform tuning to the yml rules. Never throws:
     * a failure keeps the previous catalogue in force.
     */
    public void rebuild() {
        if (settings == null || tuning.isEmpty()) {
            return;
        }
        try {
            List<AlertRule> previous = rules.get();
            List<AlertRule> next = overlay(configured);
            rules.set(next);
            for (int i = 0; i < next.size(); i++) {
                AlertRule now = next.get(i);
                AlertRule before = i < previous.size() ? previous.get(i) : null;
                if (before == null || before.threshold() != now.threshold()
                        || !before.window().equals(now.window())) {
                    log.info("Alert rule {} retuned: threshold={} window={}", now.name(),
                            now.threshold(), now.window());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Alert rule tuning could not be re-applied ({}); keeping the previous "
                    + "catalogue", e.getMessage());
        }
    }

    /** Immediate rules this audit row matches (usually zero or one). */
    public List<AlertRule> immediateMatches(AuditFacts facts) {
        List<AlertRule> hits = new ArrayList<>(1);
        for (AlertRule rule : rules.get()) {
            if (rule.trigger() == AlertRule.Trigger.IMMEDIATE && rule.matches(facts)) {
                hits.add(rule);
            }
        }
        return hits;
    }

    public List<AlertRule> windowed() {
        return rules.get().stream()
                .filter(r -> r.trigger() == AlertRule.Trigger.WINDOWED).toList();
    }

    private List<AlertRule> overlay(List<AlertRule> base) {
        if (settings == null || tuning.isEmpty()) {
            return base;
        }
        List<AlertRule> result = new ArrayList<>(base.size());
        for (AlertRule rule : base) {
            Tuning knobs = tuning.get(rule.name());
            if (knobs == null) {
                result.add(rule);
                continue;
            }
            int threshold = rule.threshold();
            if (knobs.threshold() != null && rule.trigger() == AlertRule.Trigger.WINDOWED) {
                Object stored = storedPlatformValue(knobs.threshold());
                if (stored != null) {
                    threshold = Math.max(1, (Integer) stored);
                }
            }
            Duration window = rule.window();
            if (knobs.window() != null) {
                Object stored = storedPlatformValue(knobs.window());
                if (stored != null) {
                    window = (Duration) stored;
                }
            }
            result.add(threshold == rule.threshold() && window.equals(rule.window()) ? rule
                    : new AlertRule(rule.name(), rule.description(), rule.trigger(),
                    rule.actions(), rule.outcomes(), rule.actorRoles(), rule.severity(),
                    threshold, window, rule.groupBy()));
        }
        return List.copyOf(result);
    }

    /** The platform owner's STORED value (clamped to the hard limits), else null. */
    private Object storedPlatformValue(SettingDefinition definition) {
        SettingsResolver.Resolution resolution = settings.resolveForPlatform(definition);
        return resolution.source() == SettingSource.DEFAULT ? null : resolution.value();
    }

    /** Rule name -> its threshold/window settings, from the settings' property names. */
    static Map<String, Tuning> tuningByRule(SettingsCatalogue catalogue) {
        Map<String, SettingDefinition> thresholds = new HashMap<>();
        Map<String, SettingDefinition> windows = new HashMap<>();
        for (SettingDefinition definition : catalogue.all()) {
            String property = definition.propertyName();
            if (property == null) {
                continue;
            }
            Matcher matcher = TUNING_PROPERTY.matcher(property);
            if (matcher.matches()) {
                (matcher.group(2).equals("threshold") ? thresholds : windows)
                        .put(matcher.group(1), definition);
            }
        }
        Set<String> names = new HashSet<>(thresholds.keySet());
        names.addAll(windows.keySet());
        Map<String, Tuning> byRule = new HashMap<>();
        for (String name : names) {
            byRule.put(name, new Tuning(thresholds.get(name), windows.get(name)));
        }
        return Map.copyOf(byRule);
    }

    /** One rule's tuning settings (either may be absent). */
    record Tuning(SettingDefinition threshold, SettingDefinition window) {
    }

    static List<AlertRule> build(List<AlertProperties.Rule> configured) {
        if (configured == null) {
            return List.of();
        }
        List<AlertRule> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < configured.size(); i++) {
            AlertProperties.Rule raw = configured.get(i);
            String where = "modocument.alerts.rules[" + i + "]";
            if (raw == null) {
                continue;
            }
            String name = raw.getName() == null ? "" : raw.getName().trim();
            if (name.isEmpty() || name.length() > 80) {
                throw invalid(where, "name must be 1-80 characters");
            }
            if (!names.add(name)) {
                throw invalid(where, "duplicate rule name '" + name + "'");
            }
            if (!raw.isEnabled()) {
                continue;
            }
            if (raw.getTrigger() == null) {
                throw invalid(where, "trigger (IMMEDIATE or WINDOWED) is required for '" + name + "'");
            }
            if (raw.getSeverity() == null) {
                throw invalid(where, "severity is required for '" + name + "'");
            }
            if (raw.getGroupBy() == null) {
                throw invalid(where, "group-by is required for '" + name + "'");
            }
            Set<String> actions = new LinkedHashSet<>();
            boolean any = false;
            for (String action : raw.getActions() == null ? List.<String>of() : raw.getActions()) {
                String trimmed = action == null ? "" : action.trim();
                if (trimmed.equals("*")) {
                    any = true;
                } else if (!trimmed.isEmpty()) {
                    actions.add(trimmed.toUpperCase(Locale.ROOT));
                }
            }
            if (!any && actions.isEmpty()) {
                throw invalid(where, "at least one action (or \"*\") is required for '" + name + "'");
            }
            Set<AuditOutcome> outcomes = EnumSet.noneOf(AuditOutcome.class);
            for (String outcome : raw.getOutcomes() == null ? List.<String>of() : raw.getOutcomes()) {
                if (outcome == null || outcome.isBlank()) {
                    continue;
                }
                try {
                    outcomes.add(AuditOutcome.valueOf(outcome.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw invalid(where, "unknown outcome '" + outcome + "' in '" + name + "'");
                }
            }
            Set<String> roles = new LinkedHashSet<>();
            for (String role : raw.getActorRoles() == null ? List.<String>of() : raw.getActorRoles()) {
                if (role != null && !role.isBlank()) {
                    roles.add(role.trim().toUpperCase(Locale.ROOT));
                }
            }
            Duration window = raw.getWindow();
            if (window == null || window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
                throw invalid(where, "window must be between 1m and 24h for '" + name + "'");
            }
            if (raw.getThreshold() < 1) {
                throw invalid(where, "threshold must be >= 1 for '" + name + "'");
            }
            result.add(new AlertRule(name, raw.getDescription() == null ? "" : raw.getDescription(),
                    raw.getTrigger(),
                    any ? Set.of() : Collections.unmodifiableSet(actions),
                    Collections.unmodifiableSet(outcomes),
                    Collections.unmodifiableSet(roles),
                    raw.getSeverity(),
                    raw.getTrigger() == AlertRule.Trigger.IMMEDIATE ? 1 : raw.getThreshold(),
                    window, raw.getGroupBy()));
        }
        return List.copyOf(result);
    }

    private static IllegalStateException invalid(String where, String problem) {
        return new IllegalStateException("Invalid alert rule at " + where + ": " + problem
                + ". See docs/security/alerting-and-export-caps.md.");
    }
}
