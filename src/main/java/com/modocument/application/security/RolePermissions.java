package com.modocument.application.security;

import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.modocument.domain.model.Permission.*;

/**
 * The single source of truth for which {@link Role} grants which
 * {@link Permission}s. Rendered verbatim into
 * {@code docs/security/role-permission-matrix.csv} — a test asserts the two
 * never drift apart, so a change here is always a reviewed matrix change.
 *
 * <p>Semantics:
 * <ul>
 *   <li><b>READ_ONLY</b> — every domain {@code *_READ} plus self-service on
 *       the own account and the running version. Deliberately no exports,
 *       no uploads, no LLM spend, no writes (PRIV-005: exports are Admin +
 *       Read-write only), and no operational metrics (admin-only).</li>
 *   <li><b>READ_WRITE</b> — READ_ONLY plus every domain {@code *_WRITE},
 *       uploads, LLM use, exports, extraction runs, document generation and
 *       triggering integration runs.</li>
 *   <li><b>APPROVER</b> — READ_ONLY plus the approval verbs
 *       (CHANGE_APPROVE — the ws10 maker/checker decisions; DOC_RELEASE —
 *       ws11) and AUDIT_READ, the trail they review against. Approvers
 *       review, they do not author.</li>
 *   <li><b>ADMIN</b> — everything a bank can hold: the complement of the
 *       platform-owner set and the machine-client scopes. In particular a
 *       bank admin can NOT change the shared lookup tables (LOOKUP_ADMIN
 *       affects every tenant, so it moved to the platform owner).</li>
 *   <li><b>PLATFORM_ADMIN</b> (ws21) — the platform set, plus managing
 *       their own platform-bank users, approving platform changes, reading
 *       the platform bank's own audit feed and the version. No domain
 *       permission: no access to any bank's templates, jobs or data.</li>
 *   <li><b>API_CLIENT</b> (ws26) — a bank system's machine principal: the
 *       integration scopes only, further narrowed by the client's granted
 *       scopes in its token.</li>
 * </ul>
 */
public final class RolePermissions {

    /** Permissions only the platform owner (MO Intelligence) may hold (ws21). */
    public static final Set<Permission> PLATFORM_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(PLATFORM_READ, PLATFORM_BANKS, PLATFORM_SETTINGS, PLATFORM_ANNOUNCE,
                    LOOKUP_ADMIN));

    /** Scopes only a machine API client may hold (ws26). */
    public static final Set<Permission> CLIENT_SCOPES = Collections.unmodifiableSet(
            EnumSet.of(INTEGRATION_INGEST, INTEGRATION_RESULTS, INTEGRATION_DOCS,
                    INTEGRATION_EVENTS, AGENT_CONNECT));

    // Declared after the two sets above: build() reads them.
    private static final Map<Role, Set<Permission>> MATRIX = build();

    private RolePermissions() {
    }

    /** The immutable set of permissions the role grants. */
    public static Set<Permission> granted(Role role) {
        return MATRIX.get(role);
    }

    public static boolean isGranted(Role role, Permission permission) {
        return MATRIX.get(role).contains(permission);
    }

    private static Map<Role, Set<Permission>> build() {
        EnumSet<Permission> readOnly = EnumSet.of(
                TEMPLATE_READ, VALIDATION_READ, IMPORT_READ, INGESTION_READ,
                EXTRACTION_READ, DOC_READ, LOOKUP_READ, PROFILE_SELF, SYSTEM_INFO);

        EnumSet<Permission> readWrite = EnumSet.copyOf(readOnly);
        readWrite.addAll(EnumSet.of(
                TEMPLATE_WRITE, VALIDATION_WRITE, IMPORT_WRITE, INGESTION_WRITE,
                EXTRACTION_WRITE, DOC_WRITE,
                FILE_UPLOAD, AI_USE, EXPORT_RUN, EXTRACTION_RUN, DOC_GENERATE,
                INTEGRATION_RUN));

        EnumSet<Permission> approver = EnumSet.copyOf(readOnly);
        approver.addAll(EnumSet.of(CHANGE_APPROVE, DOC_RELEASE, AUDIT_READ));

        EnumSet<Permission> notForBanks = EnumSet.copyOf(PLATFORM_PERMISSIONS);
        notForBanks.addAll(CLIENT_SCOPES);
        EnumSet<Permission> admin = EnumSet.complementOf(notForBanks);

        EnumSet<Permission> platformAdmin = EnumSet.copyOf(PLATFORM_PERMISSIONS);
        platformAdmin.addAll(EnumSet.of(PROFILE_SELF, SYSTEM_INFO, USER_ADMIN, CHANGE_APPROVE,
                SIEM_READ, LOOKUP_READ, AUDIT_READ));

        EnumSet<Permission> apiClient = EnumSet.copyOf(CLIENT_SCOPES);

        EnumMap<Role, Set<Permission>> matrix = new EnumMap<>(Role.class);
        matrix.put(Role.READ_ONLY, Collections.unmodifiableSet(readOnly));
        matrix.put(Role.READ_WRITE, Collections.unmodifiableSet(readWrite));
        matrix.put(Role.APPROVER, Collections.unmodifiableSet(approver));
        matrix.put(Role.ADMIN, Collections.unmodifiableSet(admin));
        matrix.put(Role.PLATFORM_ADMIN, Collections.unmodifiableSet(platformAdmin));
        matrix.put(Role.API_CLIENT, Collections.unmodifiableSet(apiClient));
        return Collections.unmodifiableMap(matrix);
    }
}
