package com.modocument.infrastructure.alerting;

import java.time.Instant;

/**
 * Work that rides on the alert framework's minute sweep (ws18) — e.g. the
 * SIEM webhook forwarder re-sending events whose first push failed. Kept as
 * a seam so the sweep does not depend on its participants' packages.
 * Participants must be quick, swallow their own failures and never block on
 * the network for long (the sweep's latency is the windowed rules' latency).
 */
public interface MinuteSweepParticipant {

    /** Short name for the sweep log line. */
    String name();

    void onSweep(Instant now);
}
