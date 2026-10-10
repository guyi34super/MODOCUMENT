package com.modocument.domain.model;

/**
 * How loudly an announcement is shown (ws24, docs/phase2/contracts.md §4):
 * the banner colour. Stored by name in Announcement.Severity.
 */
public enum AnnouncementSeverity {
    INFO,
    WARNING,
    CRITICAL
}
