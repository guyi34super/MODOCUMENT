package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.siem.SiemEventMapper;
import com.modocument.infrastructure.siem.SiemFeedReader;
import com.modocument.infrastructure.siem.SiemProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The bank-scoped SIEM PULL feed (ws18, SEC-031, gate E6):
 * {@code GET /api/siem/events?since=<cursor>&limit=<n>} answers
 * newline-delimited JSON ({@code application/x-ndjson}), one event per line
 * in the documented schema (docs/security/siem-event-schema.md), oldest
 * first.
 *
 * <p>ONLY the caller's own bank: the bank comes from the caller's token
 * (never a parameter), the query filters on it explicitly, and on
 * PostgreSQL the AuditEvent RLS policy filters on the request's tenant as
 * well. SIEM_READ is ADMIN-only; a bank's SIEM connector authenticates as a
 * dedicated admin account of that bank.
 *
 * <p>Paging: {@code since} is the last {@code event.id} the SIEM processed
 * (exclusive; omit it for the first call). The response carries
 * {@code X-Next-Cursor} (the id to pass as {@code since} next time; equal to
 * the request's when the page is empty) and {@code X-Has-More} (a full page
 * came back — call again immediately). Events younger than
 * {@code modocument.siem.feed.settle} are held back for one poll so a slow
 * transaction can never commit behind the cursor.
 *
 * <p>Reading the feed is not itself audited: every poll would add an event
 * to the next page (a self-feeding trail). Access is still bounded by the
 * permission, and a refused call is audited AUTHORIZATION_DENIED as usual.
 */
@RestController
@RequiresPermission(Permission.SIEM_READ)
public class SiemFeedController {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final SiemFeedReader reader;
    private final SiemEventMapper mapper;
    private final SiemProperties properties;
    private final CurrentUserProvider currentUser;

    public SiemFeedController(SiemFeedReader reader, SiemEventMapper mapper,
                              SiemProperties properties, CurrentUserProvider currentUser) {
        this.reader = reader;
        this.mapper = mapper;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/siem/events")
    public ResponseEntity<String> events(@RequestParam(required = false) String since,
                                         @RequestParam(required = false) Integer limit) {
        int bankId = currentUser.requireBankId();
        UUID cursor = parseCursor(since);
        SiemProperties.Feed feed = properties.getFeed();
        int pageSize = limit == null ? feed.getDefaultLimit() : limit;
        if (pageSize < 1 || pageSize > feed.getMaxLimit()) {
            throw new IllegalArgumentException("limit must be between 1 and " + feed.getMaxLimit() + ".");
        }
        Instant settledBefore = Instant.now().minus(feed.getSettle());

        List<AuditEvent> page = reader.page(bankId, cursor, settledBefore, pageSize);

        StringBuilder body = new StringBuilder();
        for (AuditEvent event : page) {
            body.append(mapper.toLine(event)).append('\n');
        }
        String next = page.isEmpty()
                ? (cursor == null ? "" : cursor.toString())
                : page.get(page.size() - 1).getAuditEventId().toString();
        return ResponseEntity.ok()
                .contentType(NDJSON)
                .header("X-Next-Cursor", next)
                .header("X-Has-More", String.valueOf(page.size() == pageSize))
                .header("Cache-Control", "no-store")
                .body(body.toString());
    }

    private static UUID parseCursor(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(since.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("since must be an event.id (UUID) from a previous page.");
        }
    }
}
