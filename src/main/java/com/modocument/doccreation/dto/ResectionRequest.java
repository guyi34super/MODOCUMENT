package com.modocument.doccreation.dto;

import jakarta.validation.constraints.Size;

/** Re-run the section breakdown; {@code baseVersion} guards against overwriting a concurrent edit. */
public class ResectionRequest {

    private Integer baseVersion;

    @Size(max = 500)
    private String note;

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
