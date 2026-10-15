package com.modocument.doccreation.dto;

/**
 * One reviewed item of the release queue ({@link PendingReleaseDto}), shape
 * kept from the Word path so the approver page reads it unchanged: since
 * ws31 a PARAGRAPH is one filled value ("Label: value") and an IMAGE one
 * placed photo ({@code assetFileName} its name, {@code caption} the region
 * label). {@code blockId} is the position in the queue entry.
 */
public record BlockDto(int blockId,
                       String blockType,
                       int orderIndex,
                       String text,
                       Integer assetId,
                       String caption,
                       String assetFileName) {

    public static final String TYPE_PARAGRAPH = "PARAGRAPH";
    public static final String TYPE_IMAGE = "IMAGE";
}
