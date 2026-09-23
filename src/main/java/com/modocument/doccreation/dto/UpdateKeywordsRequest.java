package com.modocument.doccreation.dto;

import java.util.List;

public class UpdateKeywordsRequest {

    private List<String> keywords;

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }
}
