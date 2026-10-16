package com.modocument.doccreation.dto;

import com.modocument.doccreation.pdftemplate.PageInfo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A PDF template with the overlay of one version (contract §5).
 * {@code version} is the version shown, {@code currentVersion} the one new
 * documents use. {@code status} is DRAFT while the uploaded proposal (version
 * 1) is under review, READY once confirmed. {@code warnings} and the sample
 * values / removed texts are review data: only present while DRAFT.
 * {@code fonts} (ws33) are the bundled font names a region may use.
 */
public record BlueprintDto(int id,
                           String name,
                           String status,
                           String flow,
                           int version,
                           int currentVersion,
                           List<PageInfo> pages,
                           List<RegionDto> regions,
                           List<EditDto> edits,
                           List<String> warnings,
                           String detectedBy,
                           int documentCount,
                           String companyId,
                           String originalFileName,
                           LocalDateTime createdDate,
                           List<String> fonts) {
}
