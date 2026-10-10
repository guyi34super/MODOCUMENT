package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AnnouncementRequest;
import com.modocument.application.dto.PlatformAnnouncementView;
import com.modocument.application.usecase.AnnouncementUseCase;
import com.modocument.application.usecase.AnnouncementValidationException;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.security.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform console's announcements (ws24, docs/phase2/contracts.md §3):
 * <ul>
 *   <li>{@code GET /api/platform/announcements} (PLATFORM_READ) →
 *       {@code {announcements:[PlatformAnnouncementView]}}, newest first,
 *       archived ones included;</li>
 *   <li>{@code POST} (201), {@code PUT /{id}} (200), {@code DELETE /{id}}
 *       (204, archives) — PLATFORM_ANNOUNCE.</li>
 * </ul>
 * Both permissions are held only by PLATFORM_ADMIN, and the permission
 * checker also requires the caller to sit in the platform bank, so a bank
 * ADMIN gets 403. Invalid input answers 400
 * {@code {error, code: "ANNOUNCEMENT_INVALID", message}}; an unknown id 404;
 * editing an archived announcement 409.
 */
@RestController
@Tag(name = "Platform announcements", description = "Platform owner console: banners shown to banks")
public class PlatformAnnouncementController {

    private final AnnouncementUseCase announcements;

    public PlatformAnnouncementController(AnnouncementUseCase announcements) {
        this.announcements = announcements;
    }

    @Operation(summary = "Every announcement of the platform, newest first (archived included)")
    @RequiresPermission(Permission.PLATFORM_READ)
    @GetMapping("/api/platform/announcements")
    public PlatformAnnouncements list() {
        return new PlatformAnnouncements(announcements.listForConsole());
    }

    @Operation(summary = "Publish an announcement (INFO, MAINTENANCE or RELEASE) to every bank "
            + "or to the listed bank codes")
    @RequiresPermission(Permission.PLATFORM_ANNOUNCE)
    @PostMapping("/api/platform/announcements")
    public ResponseEntity<PlatformAnnouncementView> create(@RequestBody AnnouncementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(announcements.create(request));
    }

    @Operation(summary = "Replace an announcement's content, window and audience")
    @RequiresPermission(Permission.PLATFORM_ANNOUNCE)
    @PutMapping("/api/platform/announcements/{id}")
    public PlatformAnnouncementView update(@PathVariable int id,
                                           @RequestBody AnnouncementRequest request) {
        return announcements.update(id, request);
    }

    @Operation(summary = "Withdraw an announcement (archived, kept as the record of what was shown)")
    @RequiresPermission(Permission.PLATFORM_ANNOUNCE)
    @DeleteMapping("/api/platform/announcements/{id}")
    public ResponseEntity<Void> archive(@PathVariable int id) {
        announcements.archive(id);
        return ResponseEntity.noContent().build();
    }

    /** 400 with the contract's error shape and a stable code. */
    @ExceptionHandler(AnnouncementValidationException.class)
    public ResponseEntity<Map<String, String>> invalid(AnnouncementValidationException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", AnnouncementValidationException.CODE);
        body.put("message", e.getMessage());
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return ResponseEntity.badRequest().body(body);
    }

    /** Response of {@code GET /api/platform/announcements}. */
    public record PlatformAnnouncements(List<PlatformAnnouncementView> announcements) {
    }
}
