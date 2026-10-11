package com.modocument.domain.model.settings;

/**
 * Who may change a setting (ws20). {@link #PLATFORM} settings are global and
 * changed only by the platform owner; {@link #BANK} settings have a platform
 * default (and, when ranged, platform bank bounds) that each bank may
 * override within those bounds.
 */
public enum SettingScope {
    PLATFORM,
    BANK
}
