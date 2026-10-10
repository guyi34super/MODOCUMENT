package com.modocument.application.dto.integration;

import java.util.List;
import java.util.Map;

/**
 * Create/update body of a flow (ws25, contracts §7.3). {@code fieldMapping}
 * pairs: INBOUND bank column → template field; OUTBOUND template field →
 * bank column/key. {@code cron}: a 6-field Spring cron expression (UTC).
 */
public record FlowRequest(String name, String direction, Long connectionId, Integer templateId,
                          String trigger, String cron, List<String> events,
                          List<String> payloadKinds, String format,
                          Map<String, Object> sourceSpec, Map<String, Object> targetSpec,
                          List<FieldMappingView> fieldMapping, Boolean maskPii, Integer rowCap,
                          Integer expectedVersion) {
}
