package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.AnnouncementView;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.usecase.AnnouncementUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The banners a signed-in user sees (ws24, docs/phase2/contracts.md §3):
 * {@code GET /api/announcements/active} →
 * {@code {announcements:[{id, kind, severity, title, body, startsAt, endsAt,
 * maintenanceStartsAt, maintenanceEndsAt, releaseVersion, dismissible}]}}.
 *
 * <p>PROFILE_SELF — every signed-in human, including during a forced
 * password change (a maintenance window matters then too). Served from the
 * in-memory announcement cache, filtered to the caller's bank and the
 * current window, newest first. Title and body are plain text: clients
 * render them escaped.
 */
@RestController
@Tag(name = "Announcements", description = "Maintenance, release and information banners")
public class AnnouncementController {

    private final AnnouncementUseCase announcements;
    private final CurrentUserProvider currentUser;

    public AnnouncementController(AnnouncementUseCase announcements, CurrentUserProvider currentUser) {
        this.announcements = announcements;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Announcements currently shown to the caller's bank, newest first; "
            + "MAINTENANCE banners are not dismissible")
    @RequiresPermission(Permission.PROFILE_SELF)
    @GetMapping("/api/announcements/active")
    public ActiveAnnouncements active() {
        return new ActiveAnnouncements(announcements.activeFor(currentUser.requireBankId()));
    }

    /** Response of {@code GET /api/announcements/active}. */
    public record ActiveAnnouncements(List<AnnouncementView> announcements) {
    }
}
