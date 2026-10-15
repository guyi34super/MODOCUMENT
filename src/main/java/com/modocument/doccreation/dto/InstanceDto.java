package com.modocument.doccreation.dto;

import com.modocument.doccreation.pdftemplate.PageInfo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A document in the builder (contract §5): the template's pages and the
 * regions of the version it was started from (fractions, no sample values),
 * its values and photos, and the keys whose text will not fit their box at
 * the minimum font size ({@code overflow}: they continue on an added page).
 */
public record InstanceDto(int id,
                          String title,
                          String status,
                          int blueprintId,
                          String blueprintName,
                          int blueprintVersion,
                          int blueprintCurrentVersion,
                          List<PageInfo> pages,
                          List<RegionDto> regions,
                          Map<String, String> values,
                          List<ImageDto> images,
                          List<String> overflow,
                          LocalDateTime lastGeneratedDate,
                          boolean pdfAvailable,
                          String reviewerComment) {

    /** A photo of the document: placed in {@code regionKey}, or in the tray when null. */
    public record ImageDto(int id, String regionKey, String originalName) {
    }
}
