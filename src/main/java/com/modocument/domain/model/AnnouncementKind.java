package com.modocument.domain.model;

/**
 * What an announcement is about (ws24, docs/phase2/contracts.md §4). Stored
 * by name in Announcement.Kind.
 * <ul>
 *   <li>{@link #INFO} — a general notice; users may dismiss it.</li>
 *   <li>{@link #MAINTENANCE} — a planned outage window
 *       (MaintenanceStartsAt/EndsAt); never dismissible, so nobody misses
 *       it.</li>
 *   <li>{@link #RELEASE} — a new version is live (ReleaseVersion); created
 *       by the deployment recorder, dismissible, and admins get a
 *       "What's new" link to the release notes.</li>
 * </ul>
 */
public enum AnnouncementKind {
    INFO,
    MAINTENANCE,
    RELEASE;

    /** Whether users may hide the banner (everything but maintenance). */
    public boolean dismissible() {
        return this != MAINTENANCE;
    }
}
