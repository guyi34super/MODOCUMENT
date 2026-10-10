package com.modocument.domain.model.settings;

/**
 * How a change to a setting is governed (ws20). {@link #OPERATIONAL} changes
 * apply at once (audited SETTING_UPDATED); {@link #SECURITY} changes go
 * through maker/checker (SETTING_UPDATE / PLATFORM_SETTING_UPDATE), as do
 * all platform bank-bound changes.
 */
public enum SettingTier {
    OPERATIONAL,
    SECURITY
}
