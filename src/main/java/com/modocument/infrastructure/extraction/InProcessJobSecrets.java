package com.modocument.infrastructure.extraction;

import com.modocument.application.port.JobSecrets;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-run credentials held in process memory only. Deliberately no
 * persistence and no toString exposure — secrets die with the process.
 */
@Component
public class InProcessJobSecrets implements JobSecrets {

    private final Map<String, Map<String, String>> secretsByJob = new ConcurrentHashMap<>();

    @Override
    public void put(String jobId, Map<String, String> secrets) {
        secretsByJob.put(jobId, Map.copyOf(secrets));
    }

    @Override
    public Optional<Map<String, String>> get(String jobId) {
        return Optional.ofNullable(secretsByJob.get(jobId));
    }

    @Override
    public void remove(String jobId) {
        secretsByJob.remove(jobId);
    }
}
