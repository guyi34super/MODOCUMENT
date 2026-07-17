package com.modocument.application.port;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;

/** Fetches one URL. Implementations: plain HTTP, Playwright (JS rendering). */
public interface WebFetcher {

    FetchResult fetch(FetchRequest request) throws Exception;
}
