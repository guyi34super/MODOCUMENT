package com.modocument.application.port;

import java.util.Map;
import java.util.Optional;

/**
 * Holds per-run credentials (database passwords, API tokens supplied at job
 * start) in process memory only — they are never persisted, logged, or sent
 * through the event bus. Tasks needing them carry a node affinity so they run
 * where the secret lives.
 */
public interface JobSecrets {

    void put(String jobId, Map<String, String> secrets);

    Optional<Map<String, String>> get(String jobId);

    void remove(String jobId);
}
