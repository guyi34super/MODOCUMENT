package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One setting as the platform layer sees it (ws20): the {@link SettingView}
 * fields plus {@code bankMin}/{@code bankMax} — the range the platform lets
 * banks choose from (null = the hard limit applies). Only ranged BANK-scope
 * settings carry bank bounds. {@code min}/{@code max} here are the hard
 * limits; {@code value}/{@code source} the platform value (PLATFORM) or the
 * shipped default (DEFAULT). {@code platformControlled} — besides this
 * default, the platform sets the value per bank
 * ({@code /api/platform/banks/{id}/settings}).
 */
public record PlatformSettingView(String key, String type, String tier, String scope,
                                  String group, String label, String description, String unit,
                                  Object value, String source,
                                  @JsonProperty("default") Object defaultValue,
                                  Object min, Object max, Object hardMin, Object hardMax,
                                  List<String> allowed, boolean requiresApproval,
                                  boolean requiresRestart, boolean locked, boolean clamped,
                                  Integer pendingChangeId, int version, Object bankMin,
                                  Object bankMax, boolean hasBankBounds,
                                  boolean platformControlled) {

    /** The bank-layer field set plus the platform's bank bounds. */
    public static PlatformSettingView of(SettingView view, Object bankMin, Object bankMax,
                                         boolean hasBankBounds) {
        return new PlatformSettingView(view.key(), view.type(), view.tier(), view.scope(),
                view.group(), view.label(), view.description(), view.unit(), view.value(),
                view.source(), view.defaultValue(), view.min(), view.max(), view.hardMin(),
                view.hardMax(), view.allowed(), view.requiresApproval(), view.requiresRestart(),
                view.locked(), view.clamped(), view.pendingChangeId(), view.version(), bankMin,
                bankMax, hasBankBounds, view.platformControlled());
    }
}
