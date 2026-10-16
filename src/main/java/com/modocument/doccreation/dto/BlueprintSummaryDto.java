package com.modocument.doccreation.dto;

import java.time.LocalDateTime;

/**
 * One entry of {@code GET /api/doccreation/blueprints} (contract §5);
 * {@code rulesCount} (ws33) — the template's field rules.
 */
public record BlueprintSummaryDto(int id,
                                  String name,
                                  String status,
                                  int currentVersion,
                                  int pageCount,
                                  String flow,
                                  int documentCount,
                                  String companyId,
                                  LocalDateTime createdDate,
                                  int rulesCount) {
}
