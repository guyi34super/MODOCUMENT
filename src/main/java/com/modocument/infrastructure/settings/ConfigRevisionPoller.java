package com.modocument.infrastructure.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cross-task settings propagation (ws20): every
 * {@code modocument.settings.poll-interval-ms} (default 5 s, yml only — not
 * itself a setting) reads the {@code ConfigRevision} counter and reloads
 * this task's snapshot when another task wrote a setting. The read and the
 * reload run as SYSTEM in their own read-only transactions inside
 * {@link SettingsCache#refreshIfChanged()} (a registered
 * {@code TenantContext.runAsSystem} call site). A failed poll is logged and
 * retried on the next tick; the last good snapshot stays in force.
 *
 * <p>{@code @EnableScheduling} comes from ExtractionSchedulingConfig, like
 * the retention/SoD/alert jobs.
 */
@Component
public class ConfigRevisionPoller {

    private static final Logger log = LoggerFactory.getLogger(ConfigRevisionPoller.class);

    private final SettingsCache cache;

    public ConfigRevisionPoller(SettingsCache cache) {
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${modocument.settings.poll-interval-ms:5000}",
            initialDelayString = "${modocument.settings.poll-interval-ms:5000}")
    public void poll() {
        try {
            if (cache.refreshIfChanged()) {
                log.debug("Settings reloaded at revision {}", cache.current().revision());
            }
        } catch (RuntimeException e) {
            log.warn("Settings poll failed ({}); keeping revision {}", e.getMessage(),
                    cache.current().revision());
        }
    }
}
