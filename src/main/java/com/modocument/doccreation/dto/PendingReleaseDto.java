package com.modocument.doccreation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One entry of the approver's release queue (ws11, gate B8 / C10): who wrote
 * and submitted the document, whether the AI composed its content
 * ({@code aiComposed} — releasing it IS the human review of the model's
 * text), and the full content blocks per section so the approver sees
 * exactly what the model wrote without owning the instance.
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

    /** A blueprint section with the author's (and the model's) content. */
    public record ReviewSectionDto(int sectionId, String title, List<BlockDto> blocks) {
    }
}
