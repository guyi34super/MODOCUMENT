package com.modocument.doccreation.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateKeywordsRequest {

    /** Joined into DocBlueprintSection.Keywords varchar(1000) by the use case. */
    @Size(max = 100)
    private List<String> keywords;

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }
}
