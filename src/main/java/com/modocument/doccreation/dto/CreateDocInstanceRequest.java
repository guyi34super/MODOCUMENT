package com.modocument.doccreation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class CreateDocInstanceRequest {

    @Positive
    private int blueprintId;

    /** DocInstance.Title is varchar(255); the use case re-checks after strip(). */
    @NotBlank
    @Size(max = 255)
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
