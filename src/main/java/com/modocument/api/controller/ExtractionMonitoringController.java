package com.modocument.api.controller;

import com.modocument.api.config.DeprecatedEndpoint;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.application.port.ExtractionTaskQueue;
import com.modocument.infrastructure.extraction.worker.ExtractionMetrics;
import com.modocument.infrastructure.extraction.worker.ExtractionWorkerPool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Operational view of the extraction machinery on this instance. */
@RestController
@Tag(name = "Extraction Monitoring")
@RequiresPermission(Permission.METRICS_READ)
public class ExtractionMonitoringController {

    private final ExtractionTaskQueue queue;
    private final ExtractionMetrics metrics;
    private final ObjectProvider<ExtractionWorkerPool> workerPool;

    public ExtractionMonitoringController(ExtractionTaskQueue queue,
                                          ExtractionMetrics metrics,
                                          ObjectProvider<ExtractionWorkerPool> workerPool) {
        this.queue = queue;
        this.metrics = metrics;
        this.workerPool = workerPool;
    }

    @DeprecatedEndpoint(replacement = "the admin dashboard (GET /api/admin/dashboard)")
    @GetMapping("/api/extraction/metrics")
    public ResponseEntity<?> metrics() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("queueDepth", queue.depth());
        view.put("counters", metrics.snapshot());
        ExtractionWorkerPool pool = workerPool.getIfAvailable();
        view.put("worker", pool == null ? Map.of("enabled", false) : pool.stats());
        return ResponseEntity.ok(view);
    }
}
