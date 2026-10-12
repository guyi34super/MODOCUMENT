package com.modocument.agent.exec;

import com.modocument.connector.core.rest.OutboundHttp;
import com.modocument.connector.core.rest.RestApiConnector;

import java.time.Clock;

/**
 * REST_API connections: connector-core's {@link RestApiConnector} — GET pull
 * with NONE/PAGE/CURSOR/LINK pagination (a next link only on the same origin
 * and path), records flattened at {@code recordsPath}; POST/PUT pushes with
 * the {@code Idempotency-Key}; NONE, BASIC, BEARER, API_KEY_HEADER, OAuth2
 * client credentials and MTLS auth from the local secret; redirects never
 * followed; responses capped; timeouts on every call. The allow-list's REST
 * path prefixes are checked against the absolute request path. A page holds
 * at most {@value #MAX_PAGE_SIZE} records (one records POST).
 */
public class RestConnector extends CoreConnector {

    /** Records per request: the server accepts at most 1000 per records POST. */
    static final int MAX_PAGE_SIZE = 1000;

    public RestConnector() {
        super(new RestApiConnector(new OutboundHttp(LIMITS, Clock.systemUTC())));
    }

    @Override
    protected int pageLimit(TaskContext task, int max) {
        return Math.min(max, MAX_PAGE_SIZE);
    }
}
