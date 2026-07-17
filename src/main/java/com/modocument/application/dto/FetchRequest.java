package com.modocument.application.dto;

/**
 * One URL fetch. etag/lastModified carry saved incremental state so the
 * server can answer 304 Not Modified for unchanged content.
 */
public record FetchRequest(String url, boolean renderJs, String etag, String lastModified) {

    public static FetchRequest plain(String url) {
        return new FetchRequest(url, false, null, null);
    }
}
