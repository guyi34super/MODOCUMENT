package com.modocument.application.security;

import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.modocument.domain.model.Permission.AI_USE;
import static com.modocument.domain.model.Permission.CHANGE_APPROVE;
import static com.modocument.domain.model.Permission.DOC_GENERATE;
import static com.modocument.domain.model.Permission.DOC_READ;
import static com.modocument.domain.model.Permission.DOC_RELEASE;
import static com.modocument.domain.model.Permission.DOC_WRITE;
import static com.modocument.domain.model.Permission.EXPORT_RUN;
import static com.modocument.domain.model.Permission.EXTRACTION_READ;
import static com.modocument.domain.model.Permission.EXTRACTION_RUN;
import static com.modocument.domain.model.Permission.EXTRACTION_WRITE;
import static com.modocument.domain.model.Permission.FILE_UPLOAD;
import static com.modocument.domain.model.Permission.IMPORT_READ;
import static com.modocument.domain.model.Permission.IMPORT_WRITE;
import static com.modocument.domain.model.Permission.INGESTION_READ;
import static com.modocument.domain.model.Permission.INGESTION_WRITE;
import static com.modocument.domain.model.Permission.LOOKUP_READ;
import static com.modocument.domain.model.Permission.PROFILE_SELF;
import static com.modocument.domain.model.Permission.TEMPLATE_READ;
import static com.modocument.domain.model.Permission.TEMPLATE_WRITE;
import static com.modocument.domain.model.Permission.VALIDATION_READ;
import static com.modocument.domain.model.Permission.VALIDATION_WRITE;

/**
 * The single source of truth for which {@link Role} grants which
 * {@link Permission}s. Rendered verbatim into
 * {@code docs/security/role-permission-matrix.csv} — a test asserts the two
 * never drift apart, so a change here is always a reviewed matrix change.
 *
 * <p>Semantics:
 * <ul>
 *   <li><b>READ_ONLY</b> — every domain {@code *_READ} plus self-service on
 *       the own account. Deliberately no exports, no uploads, no LLM spend,
 *       no writes (PRIV-005: exports are Admin + Read-write only), and no
 *       operational metrics (admin-only).</li>
 *   <li><b>READ_WRITE</b> — READ_ONLY plus every domain {@code *_WRITE},
 *       uploads, LLM use, exports, extraction runs and document
 *       generation.</li>
 *   <li><b>APPROVER</b> — READ_ONLY plus the approval verbs
 *       (CHANGE_APPROVE — the ws10 maker/checker decisions; DOC_RELEASE —
 *       routes land in ws11). Approvers review, they do not author.</li>
 *   <li><b>ADMIN</b> — everything, including the shared-lookup admin,
 *       metrics and the reserved USER_ADMIN.</li>
 * </ul>
 */
public final class RolePermissions {

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
                EXTRACTION_READ, DOC_READ, LOOKUP_READ, PROFILE_SELF);

        EnumSet<Permission> readWrite = EnumSet.copyOf(readOnly);
        readWrite.addAll(EnumSet.of(
                TEMPLATE_WRITE, VALIDATION_WRITE, IMPORT_WRITE, INGESTION_WRITE,
                EXTRACTION_WRITE, DOC_WRITE,
                FILE_UPLOAD, AI_USE, EXPORT_RUN, EXTRACTION_RUN, DOC_GENERATE));

        EnumSet<Permission> approver = EnumSet.copyOf(readOnly);
        approver.addAll(EnumSet.of(CHANGE_APPROVE, DOC_RELEASE));

        EnumSet<Permission> admin = EnumSet.allOf(Permission.class);

        EnumMap<Role, Set<Permission>> matrix = new EnumMap<>(Role.class);
        matrix.put(Role.READ_ONLY, Collections.unmodifiableSet(readOnly));
        matrix.put(Role.READ_WRITE, Collections.unmodifiableSet(readWrite));
        matrix.put(Role.APPROVER, Collections.unmodifiableSet(approver));
        matrix.put(Role.ADMIN, Collections.unmodifiableSet(admin));
        return Collections.unmodifiableMap(matrix);
    }
}
