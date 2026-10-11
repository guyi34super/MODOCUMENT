package com.modocument.domain.model.settings;

/**
 * "The effective settings may have changed" (ws20): published by the
 * settings cache every time THIS task loads a new snapshot — after a
 * settings write commits here, and when the ConfigRevisionPoller picks up a
 * change written by another task. Consumers that pre-compute something from
 * settings (the alert rule catalogue) rebuild on it; everything else reads
 * per call and needs no listener.
 *
 * <p>Delivered synchronously on the reloading thread, never inside the
 * writer's transaction (the reload runs after commit).
 *
 * @param revision the ConfigRevision the new snapshot reflects
 */
public record SettingsChangedEvent(long revision) {
}
