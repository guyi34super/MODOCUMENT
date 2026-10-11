package com.modocument.domain.model.settings;

/** The layer an effective setting value came from (ws20). */
public enum SettingSource {
    /** The bank's own override. */
    BANK,
    /** The platform owner's default for every bank. */
    PLATFORM,
    /** The shipped default: the application.yml property (or the code default). */
    DEFAULT
}
