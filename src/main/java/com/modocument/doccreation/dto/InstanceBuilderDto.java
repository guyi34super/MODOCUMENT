package com.modocument.doccreation.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Everything the builder page needs to render a document instance. */
public record InstanceBuilderDto(int instanceId,
                                 String title,
                                 int blueprintId,
                                 String blueprintName,
                                 String status,
                                 LocalDateTime lastGeneratedDate,
                                 boolean pdfAvailable,
                                 boolean aiComposed,
                                 LocalDateTime submittedAt,
                                 LocalDateTime releasedAt,
                                 String reviewComment,
                                 List<BuilderSectionDto> sections,
                                 List<UnsortedAssetDto> unsortedAssets) {

    public record BuilderSectionDto(int sectionId,
                                    int orderIndex,
                                    String title,
                                    int headingLevel,
                                    List<String> keywords,
                                    List<BlockDto> blocks) {
    }

    public record UnsortedAssetDto(int assetId, String originalFileName) {
    }
}
