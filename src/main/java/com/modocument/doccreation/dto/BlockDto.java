package com.modocument.doccreation.dto;

import com.modocument.doccreation.entity.DocContentBlock;

public record BlockDto(int blockId,
                       String blockType,
                       int orderIndex,
                       String text,
                       Integer assetId,
                       String caption,
                       String assetFileName) {

    public static BlockDto from(DocContentBlock block, String assetFileName) {
        return new BlockDto(block.getDocContentBlockId(), block.getBlockType(), block.getOrderIndex(),
                block.getTextContent(), block.getAssetId(), block.getCaption(), assetFileName);
    }
}
