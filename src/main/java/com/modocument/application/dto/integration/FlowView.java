package com.modocument.application.dto.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A flow as the admin sees it (ws25, contracts §7.3). */
public record FlowView(long id, String name, String direction, long connectionId,
                       String connectionName, Integer templateId, String templateName,
                       String trigger, String cron, List<String> events, List<String> payloadKinds,
                       String format, Map<String, Object> sourceSpec, Map<String, Object> targetSpec,
                       List<FieldMappingView> fieldMapping, boolean maskPii, Integer rowCap,
                       boolean enabled, Integer pendingChangeId, int version, Instant nextRunAt,
                       LastRun lastRun) {

    /** The newest run of the flow, or null. */
    public record LastRun(long id, String status, Instant finishedAt) {
    }
}
