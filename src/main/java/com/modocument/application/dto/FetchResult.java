package com.modocument.application.dto;

/** Outcome of one URL fetch. */
public record FetchResult(int status, String contentType, String body, String etag,
                          String lastModified, boolean truncated) {

    public boolean notModified() {
        return status == 304;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
