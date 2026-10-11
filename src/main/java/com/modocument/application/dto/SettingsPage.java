package com.modocument.application.dto;

import java.util.List;

/**
 * GET /api/admin/settings and GET /api/platform/settings (ws20):
 * {@code {layer:"BANK"|"PLATFORM", revision, groups:[{id, label, settings}]}}.
 * {@code revision} is the ConfigRevision the values reflect.
 *
 * @param <V> {@link SettingView} (bank layer) or {@link PlatformSettingView}
 */
public record SettingsPage<V>(String layer, long revision, List<Group<V>> groups) {

    /** One tab of the settings screen. */
    public record Group<V>(String id, String label, List<V> settings) {
    }
}
