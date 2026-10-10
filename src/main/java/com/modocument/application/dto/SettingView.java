package com.modocument.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One setting as the bank layer sees it (ws20, docs/phase2/contracts.md §3).
 * Values are JSON-typed: numbers for INT/DECIMAL, booleans, strings for
 * ENUM and DURATION (canonical {@code "15m"} form).
 *
 * <ul>
 *   <li>{@code value}/{@code source} — the effective value and the layer it
 *       came from (BANK, PLATFORM or DEFAULT);</li>
 *   <li>{@code default} — the shipped default (yml/env, else code);</li>
 *   <li>{@code min}/{@code max} — what this layer may choose now (hard limits
 *       narrowed by the platform's bank bounds); {@code hardMin}/{@code hardMax}
 *       — the guardrails nobody can override; {@code allowed} — the choices of
 *       a BOOLEAN/ENUM setting;</li>
 *   <li>{@code requiresApproval} — a change goes through maker/checker;
 *       {@code locked} — exactly one allowed value; {@code clamped} — the
 *       stored value lies outside the current limits and was corrected;</li>
 *   <li>{@code pendingChangeId} — the open change covering this key, if any;
 *       {@code version} — send it back as {@code expectedVersion}.</li>
 * </ul>
 */
public record SettingView(String key, String type, String tier, String scope, String group,
                          String label, String description, String unit, Object value,
                          String source, @JsonProperty("default") Object defaultValue,
                          Object min, Object max, Object hardMin, Object hardMax,
                          List<String> allowed, boolean requiresApproval,
                          boolean requiresRestart, boolean locked, boolean clamped,
                          Integer pendingChangeId, int version) {
}
