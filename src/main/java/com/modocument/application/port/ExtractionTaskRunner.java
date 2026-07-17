package com.modocument.application.port;

import com.modocument.domain.document.ExtractionTask;

/**
 * Executes one claimed extraction task to its terminal state (complete, fail
 * with backoff, or skip). The worker pool stays a pure scheduling concern by
 * delegating the actual work here.
 */
public interface ExtractionTaskRunner {

    void run(ExtractionTask task, String workerId);
}
