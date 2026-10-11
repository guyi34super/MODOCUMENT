package com.modocument.infrastructure.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Guardrails on the SHIPPED defaults (ws20): a yml/env value outside its
 * setting's hard limits (e.g. {@code MODOCUMENT_RETENTION_DAYS=90} against
 * the gate-C5 ceiling of 30, or a locked switch turned off) is reported at
 * startup.
 *
 * <ul>
 *   <li>Default — one WARN per violation; the value stays in force exactly
 *       as configured (dev and test profiles switch e.g. retention off on
 *       purpose).</li>
 *   <li>{@code modocument.settings.enforce-guardrails=true}
 *       ({@code MODOCUMENT_SETTINGS_ENFORCE_GUARDRAILS}, set in staging and
 *       production) — the context refuses to start, listing every
 *       violation. Stored values never get this far: they are clamped (or
 *       refused at write time).</li>
 * </ul>
 * Runs while the context is being built (a constructor), so a refused boot
 * never opens the HTTP port.
 */
@Component
public class SettingsStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SettingsStartupCheck.class);

    private final List<SettingsCatalogue.DefaultViolation> violations;

    public SettingsStartupCheck(SettingsCatalogue catalogue,
                                @Value("${modocument.settings.enforce-guardrails:false}")
                                boolean enforceGuardrails) {
        this.violations = catalogue.defaultViolations();
        check(violations, enforceGuardrails);
    }

    /** The violations found at startup (empty when every default is in range). */
    public List<SettingsCatalogue.DefaultViolation> violations() {
        return violations;
    }

    static void check(List<SettingsCatalogue.DefaultViolation> violations, boolean enforce) {
        if (violations.isEmpty()) {
            return;
        }
        if (enforce) {
            StringBuilder message = new StringBuilder("Refusing to start: ")
                    .append(violations.size())
                    .append(" configured default(s) violate the settings guardrails "
                            + "(modocument.settings.enforce-guardrails=true):");
            for (SettingsCatalogue.DefaultViolation violation : violations) {
                message.append("\n  - ").append(violation.describe());
            }
            throw new IllegalStateException(message.toString());
        }
        for (SettingsCatalogue.DefaultViolation violation : violations) {
            log.warn("Settings guardrail: {} — tolerated because "
                    + "modocument.settings.enforce-guardrails is off; staging/production "
                    + "refuse to start with it", violation.describe());
        }
    }
}
