package com.modocument.doccreation.dto;

public class UpdateBlockRequest {

    private String text;
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
