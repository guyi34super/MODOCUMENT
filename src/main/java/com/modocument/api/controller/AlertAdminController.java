package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.entity.AlertLog;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.persistence.AlertLogJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin view of the security alerts raised for the caller's bank (ws18,
 * gate D1): {@code GET /api/admin/alerts?limit=50}, newest first, with the
 * delivery evidence (channels that accepted the alert, status, created ->
 * delivered timestamps).
 *
 * <p>USER_ADMIN on purpose, like the retention and SoD admin surfaces:
 * reviewing the bank's security alerts is a governance function of the
 * bank Admin, so no new permission is minted for it. Bank-scoped by the
 * caller's token (explicit filter) and, on PostgreSQL, by the AlertLog RLS
 * policy — another bank's admin never sees these rows.
 */
@RestController
@RequiresPermission(Permission.USER_ADMIN)
public class AlertAdminController {

    private static final int MAX_LIMIT = 200;

    private final AlertLogJpaRepository alertLogs;
    private final CurrentUserProvider currentUser;

    public AlertAdminController(AlertLogJpaRepository alertLogs, CurrentUserProvider currentUser) {
        this.alertLogs = alertLogs;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/admin/alerts")
    public ResponseEntity<List<Map<String, Object>>> recent(
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        int bankId = currentUser.requireBankId();
        List<Map<String, Object>> body = alertLogs
                .findByBankIdOrderByCreatedAtDescAlertLogIdDesc(bankId, PageRequest.of(0, limit))
                .stream()
                .map(AlertAdminController::view)
                .toList();
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> view(AlertLog row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("alertId", row.getAlertLogId());
        view.put("rule", row.getRuleName());
        view.put("severity", row.getSeverity().name());
        view.put("summary", row.getSummary());
        view.put("eventCount", row.getEventCount());
        view.put("auditEventId", row.getAuditEventId());
        view.put("createdAt", row.getCreatedAt());
        view.put("deliveredAt", row.getDeliveredAt());
        view.put("channels", row.getChannel());
        view.put("status", row.getStatus().name());
        return view;
    }
}
