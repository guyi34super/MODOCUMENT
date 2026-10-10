package com.modocument.api.controller;

import com.modocument.infrastructure.ratelimit.FixedWindowRateLimiter;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synthetic probe for external uptime monitoring (ws18, SUP-S11 share):
 * {@code GET /api/probe/synthetic} — PUBLIC (no token: an external monitor
 * must not hold credentials), rate-limited per client ip, and deliberately
 * terse:
 * <pre>{"status":"UP","version":"1.0.0","db":true,"checkedAt":"..."}</pre>
 * Unlike {@code /actuator/health} (a container liveness check), the probe
 * travels the whole application path — the proxy, the security filter
 * chain, Spring MVC and a real database round trip through the
 * application's connection pool — so a monitor polling it sees what a user
 * would see. 200 when the database answered, 503 when it did not.
 *
 * <p>NO INTERNAL DETAILS: no exception text, host names, pool statistics or
 * schema information — only the build version and one boolean. A failure
 * is logged server-side (redacted like every line).
 */
@RestController
@Tag(name = "Synthetic Probe", description = "Public uptime probe: build version + database round trip (rate-limited per IP)")
public class SyntheticProbeController {

    private static final Logger log = LoggerFactory.getLogger(SyntheticProbeController.class);

    private final JdbcTemplate jdbc;
    private final String version;
    private final FixedWindowRateLimiter perIp;

    public SyntheticProbeController(JdbcTemplate jdbc, ObjectProvider<BuildProperties> build,
                                    @Value("${modocument.probe.rate-limit.per-ip-max:60}") int perIpMax,
                                    @Value("${modocument.probe.rate-limit.window-millis:60000}") long windowMillis) {
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(2);
        this.version = resolveVersion(build.getIfAvailable());
        this.perIp = new FixedWindowRateLimiter(perIpMax, windowMillis, 10_000);
    }

    @GetMapping("/api/probe/synthetic")
    public ResponseEntity<Map<String, Object>> probe(HttpServletRequest request) {
        String key = "probe:" + request.getRemoteAddr();
        long now = System.currentTimeMillis();
        if (!perIp.tryAcquire(key, now)) {
            throw new TooManyRequestsException("Probe rate limit exceeded.",
                    perIp.retryAfterSeconds(key, now));
        }
        boolean dbOk = databaseRoundTrip();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbOk ? "UP" : "DOWN");
        body.put("version", version);
        body.put("db", dbOk);
        body.put("checkedAt", Instant.now().toString());
        return ResponseEntity.status(dbOk ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .header("Cache-Control", "no-store")
                .body(body);
    }

    private boolean databaseRoundTrip() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            log.warn("Synthetic probe: database round trip failed ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    private String resolveVersion(BuildProperties build) {
        if (build != null && build.getVersion() != null) {
            return build.getVersion();
        }
        String implementation = SyntheticProbeController.class.getPackage().getImplementationVersion();
        return implementation == null ? "unknown" : implementation;
    }
}
