package com.modocument.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.modocument.api.security.RequiresPermission;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.sod.SodScanJob;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin surface of the nightly SoD scan (ws11, SEC-024 layer 3).
 * USER_ADMIN on purpose: like the retention run, verifying segregation of
 * duties is a governance function of the bank Admin — no new permission is
 * minted for it (documented in docs/security/role-catalogue-and-sod.md).
 *
 * <p>POST /api/admin/sod/scan — manual trigger of the same sweep the
 * scheduler runs nightly. It only re-verifies invariants (it changes
 * nothing), so an early manual run is always safe; the response carries the
 * caller's own bank's counts, cross-bank evidence stays in each bank's own
 * audit trail (SOD_SCAN_COMPLETED / SOD_VIOLATION_FOUND).
 */
@RestController
@Tag(name = "SoD Admin", description = "Separation-of-duties scan (admin)")
@RequiresPermission(Permission.USER_ADMIN)
public class SodAdminController {

    private final SodScanJob job;
    private final CurrentUserProvider currentUser;

    public SodAdminController(SodScanJob job, CurrentUserProvider currentUser) {
        this.job = job;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Run the separation-of-duties conflict scan now")
    @PostMapping("/api/admin/sod/scan")
    public ResponseEntity<?> scan() {
        int bankId = currentUser.requireBankId();
        List<SodScanJob.BankScanSummary> summaries = job.runOnce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("banksScanned", summaries.size());
        body.put("totalFindings",
                summaries.stream().mapToInt(SodScanJob.BankScanSummary::findings).sum());
        summaries.stream()
                .filter(summary -> summary.bankId() == bankId)
                .findFirst()
                .ifPresent(summary -> body.put("yourBank", summary.counts()));
        return ResponseEntity.ok(body);
    }
}
