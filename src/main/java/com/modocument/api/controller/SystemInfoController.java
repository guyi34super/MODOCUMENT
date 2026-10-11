package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.domain.model.Permission;
import com.modocument.infrastructure.release.ReleaseNotes;
import com.modocument.infrastructure.release.ReleaseNotesCatalog;
import com.modocument.infrastructure.release.SystemVersionService;
import com.modocument.infrastructure.release.VersionInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What is running and what changed (ws24, docs/phase2/contracts.md §3):
 * <ul>
 *   <li>{@code GET /api/system/version} →
 *       {@code {version, commit, buildTime, schemaVersion, deployedAt, environment}}
 *       (fields may be null — see {@link VersionInfo});</li>
 *   <li>{@code GET /api/system/release-notes?limit=5} →
 *       {@code {releases:[{version, date, sections:{Added:[…], …}}]}}, the
 *       CHANGELOG.md bundled with this build, newest first, limit 1–20
 *       (400 outside).</li>
 * </ul>
 * {@code SYSTEM_INFO} is held by every human role (bank roles and the
 * platform admin), so any signed-in user can see the version they run; the
 * answers carry no tenant data. Anonymous callers get 401 — the public
 * uptime probe ({@code /api/probe/synthetic}) is the only anonymous version
 * source and reports the version alone.
 */
@RestController
@Tag(name = "System", description = "Running version, schema version and release notes")
@RequiresPermission(Permission.SYSTEM_INFO)
public class SystemInfoController {

    private final SystemVersionService versions;
    private final ReleaseNotesCatalog releaseNotes;

    public SystemInfoController(SystemVersionService versions, ReleaseNotesCatalog releaseNotes) {
        this.versions = versions;
        this.releaseNotes = releaseNotes;
    }

    @Operation(summary = "Running version, commit, build time, database schema version and environment")
    @GetMapping("/api/system/version")
    public VersionInfo version() {
        return versions.current();
    }

    @Operation(summary = "Release notes of the newest releases (bundled CHANGELOG.md), newest first; "
            + "limit 1-20, default 5")
    @GetMapping("/api/system/release-notes")
    public ReleaseNotesView releaseNotes(@RequestParam(defaultValue = "5") int limit) {
        return new ReleaseNotesView(releaseNotes.latest(limit));
    }

    /** Response of {@code GET /api/system/release-notes}. */
    public record ReleaseNotesView(List<ReleaseNotes> releases) {
    }
}
