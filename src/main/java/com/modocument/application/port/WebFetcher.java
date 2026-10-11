package com.modocument.application.port;

import com.modocument.application.dto.FetchRequest;
import com.modocument.application.dto.FetchResult;

/** Fetches one URL over plain HTTP (JS rendering was removed in ws29). */
public interface WebFetcher {

    FetchResult fetch(FetchRequest request) throws Exception;
}
