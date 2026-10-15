package com.modocument.doccreation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One entry of the approver's release queue (ws11, gate B8 / C10): who wrote
 * and submitted the document, whether the AI composed its content
 * ({@code aiComposed}; always false for PDF templates), and the document's
 * content: one section per region group, each filled value a PARAGRAPH
 * block ("Label: value") and each placed photo an IMAGE block, so the
 * approver sees exactly what was entered without owning the instance.
 */
public record PendingReleaseDto(int instanceId,
                                String title,
                                String blueprintName,
                                int ownerUserId,
                                String ownerEmail,
                                Integer submittedByUserId,
                                String submittedByEmail,
                                LocalDateTime submittedAt,
                                LocalDateTime lastGeneratedDate,
                                boolean aiComposed,
                                List<ReviewSectionDto> sections) {

    /** A region group of the template with the author's values and photos. */
    public record ReviewSectionDto(int sectionId, String title, List<BlockDto> blocks) {
    }
}
