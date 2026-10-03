package com.modocument.doccreation.dto;

import jakarta.validation.constraints.Size;

public class UpdateBlockRequest {

    @Size(max = 100_000)
    private String text;

    /** DocContentBlock.Caption is varchar(500). */
    @Size(max = 500)
    private String caption;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }
}
