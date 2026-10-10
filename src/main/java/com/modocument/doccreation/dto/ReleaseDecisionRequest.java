package com.modocument.doccreation.dto;

import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/doccreation/instances/{id}/release and /reject
 * (ws11 approver side). The comment is the recorded human review (C10).
 */
public class ReleaseDecisionRequest {

    @Size(max = 500)
    private String comment;

    public ReleaseDecisionRequest() {
    }

    public ReleaseDecisionRequest(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
