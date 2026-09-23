package com.modocument.doccreation.dto;

public class CreateDocInstanceRequest {

    private int blueprintId;
    private String title;

    public int getBlueprintId() {
        return blueprintId;
    }

    public void setBlueprintId(int blueprintId) {
        this.blueprintId = blueprintId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
