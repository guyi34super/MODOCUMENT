package com.modocument.infrastructure.alerting;

import com.modocument.domain.model.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 */
@Component
public class AlertRuleCatalogue {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleCatalogue.class);

    static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    static final Duration MAX_WINDOW = Duration.ofHours(24);

    private final List<AlertRule> rules;

    public AlertRuleCatalogue(AlertProperties properties) {
        this.rules = build(properties.getRules());
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

    public List<AlertRule> all() {
        return rules;
    }

    /** Immediate rules this audit row matches (usually zero or one). */
    public List<AlertRule> immediateMatches(AuditFacts facts) {
        List<AlertRule> hits = new ArrayList<>(1);
        for (AlertRule rule : rules) {
            if (rule.trigger() == AlertRule.Trigger.IMMEDIATE && rule.matches(facts)) {
                hits.add(rule);
            }
        }
        return hits;
    }

    public List<AlertRule> windowed() {
        return rules.stream().filter(r -> r.trigger() == AlertRule.Trigger.WINDOWED).toList();
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
