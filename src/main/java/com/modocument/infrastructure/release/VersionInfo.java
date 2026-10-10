package com.modocument.infrastructure.release;

/**
 * What is running, as served by {@code GET /api/system/version} (ws24,
 * docs/phase2/contracts.md §3). Every field but {@code environment} may be
 * null: a build without Maven's build-info/git metadata, a database without
 * Flyway (the H2 test profile), or — until the Wave 2 deployment recorder
 * (V240) — no recorded deployment.
 *
 * @param version       release version from pom.xml (BuildProperties)
 * @param commit        abbreviated git commit id (GitProperties)
 * @param buildTime     ISO-8601 build instant (BuildProperties)
 * @param schemaVersion latest applied Flyway migration version, e.g. {@code "161"}
 * @param deployedAt    ISO-8601 instant this version was first started in
 *                      this environment
 * @param environment   deployment name (MODOCUMENT_DEPLOY_ENVIRONMENT:
 *                      staging, production; {@code local} by default)
 */
public record VersionInfo(String version, String commit, String buildTime, String schemaVersion,
                          String deployedAt, String environment) {
}
