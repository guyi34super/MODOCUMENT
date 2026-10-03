package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AddBlockRequest {

    /** PARAGRAPH | IMAGE. */
    @NotBlank
    @Size(max = 20)
    private String blockType;

    @Size(max = 100_000)
    private String text;

    private Integer assetId;

    /** DocContentBlock.Caption is varchar(500). */
    @Size(max = 500)
    private String caption;

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Integer getAssetId() {
        return assetId;
    }

    public void setAssetId(Integer assetId) {
        this.assetId = assetId;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }
}
