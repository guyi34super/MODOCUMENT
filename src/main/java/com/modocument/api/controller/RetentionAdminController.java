package com.modocument.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.AuditEvent;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.retention.BankPurgeSummary;
import com.modocument.infrastructure.retention.RetentionProperties;
import com.modocument.infrastructure.retention.RetentionPurgeJob;
import com.modocument.infrastructure.retention.RetentionRunHistory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin surface of the 30-day retention purge (ws14, gate C5, PRIV-013).
 * USER_ADMIN on purpose: retention is a data-governance function of the
 * bank Admin, exactly like user administration — no new permission is
 * minted for it (documented in docs/security/retention-and-dsr.md).
 *
 * <ul>
 *   <li>POST /api/admin/retention/run — manual trigger of the same sweep
 *       the scheduler runs daily. It only enforces the globally configured
 *       policy (anything it deletes was due for deletion anyway), so an
 *       early manual run is always safe; the response carries the caller's
 *       own bank's counts, cross-bank evidence stays in each bank's own
 *       audit trail.</li>
 *   <li>GET /api/admin/retention/status — policy + the caller's bank's
 *       last RETENTION_PURGE_COMPLETED audit event (the "job log"
 *       evidence), for the admin panel.</li>
 * </ul>
 */
@RestController
@RequiresPermission(Permission.USER_ADMIN)
public class RetentionAdminController {

    private final RetentionPurgeJob job;
    private final RetentionProperties properties;
    private final RetentionRunHistory history;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public RetentionAdminController(RetentionPurgeJob job, RetentionProperties properties,
                                    RetentionRunHistory history, CurrentUserProvider currentUser,
                                    ObjectMapper objectMapper) {
        this.job = job;
        this.properties = properties;
        this.history = history;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/admin/retention/run")
    public ResponseEntity<?> run() {
        int bankId = currentUser.requireBankId();
        List<BankPurgeSummary> summaries = job.runOnce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("retentionDays", properties.customerDataDays());
        body.put("banksSwept", summaries.size());
        summaries.stream()
                .filter(summary -> summary.bankId() == bankId)
                .findFirst()
                .ifPresent(summary -> body.put("yourBank", summary.counts()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/admin/retention/status")
    public ResponseEntity<?> status() {
        int bankId = currentUser.requireBankId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.enabled());
        body.put("retentionDays", properties.customerDataDays());
        body.put("lastRun", history.latestFor(bankId).map(this::describe).orElse(null));
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> describe(AuditEvent event) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("occurredAt", event.getOccurredAt());
        run.put("counts", readCounts(event.getNewValues()));
        return run;
    }

    private JsonNode readCounts(String newValuesJson) {
        try {
            return newValuesJson == null ? null : objectMapper.readTree(newValuesJson);
        } catch (Exception e) {
            return null;
        }
    }
}
