package com.modocument.doccreation.dto;

public class AssignAssetRequest {

    /** Target section, or null to move the image back to the Unsorted tray. */
    private Integer sectionId;

    public Integer getSectionId() {
        return sectionId;
    }

    public void setSectionId(Integer sectionId) {
        this.sectionId = sectionId;
    }
}
