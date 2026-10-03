package com.modocument.application.security;

import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed role×permission matrix (docs/security/role-permission-matrix.csv)
 * IS the code's matrix: this test renders {@link RolePermissions} to the
 * canonical CSV and asserts byte-equality, so any change to the grants is a
 * visible, reviewable diff of the document — and any edit to the document
 * that the code does not back fails the build.
 *
 * <p>On a legitimate matrix change: update RolePermissions, run
 * {@link #renderCanonicalCsv()} (the failure message prints it) and commit
 * the new CSV alongside.
 */
class RolePermissionMatrixTest {

    private static final Path MATRIX_FILE =
            Path.of("docs", "security", "role-permission-matrix.csv");

    @Test
    void committedMatrixCsvMatchesTheCodeExactly() throws IOException {
        String rendered = renderCanonicalCsv();

        assertThat(MATRIX_FILE)
                .as("docs/security/role-permission-matrix.csv must exist (generated from "
                        + "RolePermissions; see this test's javadoc)")
                .exists();
        String committed = new String(Files.readAllBytes(MATRIX_FILE), StandardCharsets.UTF_8);

        assertThat(committed)
                .as("docs/security/role-permission-matrix.csv has drifted from "
                        + "RolePermissions. Expected content:%n%s", rendered)
                .isEqualTo(rendered);
    }

    @Test
    void roleSemanticsHold() {
        // ADMIN holds everything.
        assertThat(RolePermissions.granted(Role.ADMIN))
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Permission.class));

        // READ_ONLY: no writes, no exports, no uploads, no LLM spend (PRIV-005).
        for (Permission permission : RolePermissions.granted(Role.READ_ONLY)) {
            assertThat(permission.name()).doesNotEndWith("_WRITE");
        }
        assertThat(RolePermissions.granted(Role.READ_ONLY)).doesNotContain(
                Permission.EXPORT_RUN, Permission.FILE_UPLOAD, Permission.AI_USE,
                Permission.LOOKUP_ADMIN, Permission.METRICS_READ, Permission.USER_ADMIN);

        // READ_WRITE is a strict superset of READ_ONLY but never an admin.
        assertThat(RolePermissions.granted(Role.READ_WRITE))
                .containsAll(RolePermissions.granted(Role.READ_ONLY))
                .doesNotContain(Permission.LOOKUP_ADMIN, Permission.METRICS_READ,
                        Permission.USER_ADMIN, Permission.CHANGE_APPROVE, Permission.DOC_RELEASE);

        // Exports: Admin + Read-write only (PRIV-005).
        for (Role role : Role.values()) {
            assertThat(RolePermissions.isGranted(role, Permission.EXPORT_RUN))
                    .as("EXPORT_RUN for %s", role)
                    .isEqualTo(role == Role.ADMIN || role == Role.READ_WRITE);
        }

        // APPROVER reviews: READ_ONLY plus the approval verbs, nothing else.
        EnumSet<Permission> expectedApprover =
                EnumSet.copyOf(RolePermissions.granted(Role.READ_ONLY));
        expectedApprover.add(Permission.CHANGE_APPROVE);
        expectedApprover.add(Permission.DOC_RELEASE);
        assertThat(RolePermissions.granted(Role.APPROVER))
                .containsExactlyInAnyOrderElementsOf(expectedApprover);
    }

    /**
     * Canonical rendering: permissions in enum order, roles in enum order,
     * {@code x} for granted, empty for denied, {@code \n} line endings, one
     * trailing newline.
     */
    static String renderCanonicalCsv() {
        StringBuilder csv = new StringBuilder("permission,code");
        for (Role role : Role.values()) {
            csv.append(',').append(role.name());
        }
        csv.append('\n');
        for (Permission permission : Permission.values()) {
            csv.append(permission.name()).append(',').append(permission.code());
            for (Role role : Role.values()) {
                csv.append(',').append(RolePermissions.isGranted(role, permission) ? "x" : "");
            }
            csv.append('\n');
        }
        return csv.toString();
    }
}
