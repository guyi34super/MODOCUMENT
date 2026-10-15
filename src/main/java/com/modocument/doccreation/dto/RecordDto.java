package com.modocument.doccreation.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * A validated record of a flow template (docs/doccreation/flow-records-contract.md
 * §4): its values (template field name to value) masked per the caller's
 * role, the job and 1-based row it came from.
 */
public record RecordDto(long recordId,
                        int templateId,
                        String templateName,
                        int jobId,
                        int rowIndex,
                        Map<String, String> values,
                        LocalDateTime createdAt) {
}
