package com.modocument.doccreation.dto;

import java.time.LocalDateTime;

/**
 * One entry of {@code GET /api/doccreation/blueprints} (contract §5);
 * {@code rulesCount} (ws33) — the template's field rules.
 *
 * <p>ws34 (data-templates-contract §3, the "Map fields" list): the chosen
 * data template ({@code dataTemplateId}/{@code dataTemplateName}, null when
 * none), the regions of the current version mapped to one of its fields
 * ({@code mappedCount}) and the regions that have at least one compatible
 * field ({@code mappableCount}; 0 without a data template).
 * {@code validationEnabled} (§8): the default for new documents.
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
                                  int rulesCount,
                                  Integer dataTemplateId,
                                  String dataTemplateName,
                                  int mappedCount,
                                  int mappableCount,
                                  boolean validationEnabled) {
}
