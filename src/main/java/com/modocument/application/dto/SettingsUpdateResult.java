package com.modocument.application.dto;

import java.util.List;

/**
 * The answer to a settings PUT (ws20): {@code applied} — the settings that
 * changed at once (operational tier), as they now read; {@code pendingChange}
 * — the maker/checker request holding the security-tier part, or null. The
 * controller answers 202 when a change is pending, 200 otherwise.
 *
 * @param <V> {@link SettingView} or {@link PlatformSettingView}
 */
public record SettingsUpdateResult<V>(List<V> applied, PendingChangeDto pendingChange) {
}
