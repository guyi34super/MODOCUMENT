package com.modocument.application.dto;

import java.time.LocalDateTime;

/** One import of the dashboard's recent-activity list (ws28): ids, names and the date only. */
public record ImportSummary(int importHistoryId,
                            String fileName,
                            int templateId,
                            String templateName,
                            LocalDateTime importDate) {
}
