package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.CreateUserRequest;
import com.modocument.application.dto.UpdateUserDisabledRequest;
import com.modocument.application.dto.UpdateUserRoleRequest;
import com.modocument.application.usecase.AccessReviewUseCase;
import com.modocument.application.usecase.UserAdminUseCase;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Bank-scoped user administration (ws09, gates B1/B10, PRIV-017). Every
 * route requires USER_ADMIN (ADMIN only — class-level annotation), and every
 * operation is scoped to the CALLER'S bank inside the use cases: a user of
 * another bank 404s like a non-existent id. Error mapping rides the global
 * ApiExceptionHandler: unknown/foreign user → 404, bad role or weak
 * temporary password → 400, self-targeting and the last-admin guard → 409.
 */
@RestController
@RequiresPermission(Permission.USER_ADMIN)
public class UserAdminController {

    private final UserAdminUseCase userAdmin;
    private final AccessReviewUseCase accessReview;

    public UserAdminController(UserAdminUseCase userAdmin, AccessReviewUseCase accessReview) {
        this.userAdmin = userAdmin;
        this.accessReview = accessReview;
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(userAdmin.listUsers());
    }

    @PostMapping("/api/admin/users")
    public ResponseEntity<?> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAdmin.createUser(
                request.getEmail(), request.getDisplayName(), parseRole(request.getRole()),
                request.getTemporaryPassword()));
    }

    @PutMapping("/api/admin/users/{id}/role")
    public ResponseEntity<?> changeRole(@PathVariable int id,
                                        @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok(userAdmin.changeRole(id, parseRole(request.getRole())));
    }

    @PutMapping("/api/admin/users/{id}/disabled")
    public ResponseEntity<?> setDisabled(@PathVariable int id,
                                         @Valid @RequestBody UpdateUserDisabledRequest request) {
        return ResponseEntity.ok(userAdmin.setDisabled(id, request.getDisabled()));
    }

    /** Gate B10: the caller's bank's users with last-login dates, as CSV. */
    @GetMapping("/api/admin/users/export")
    public ResponseEntity<byte[]> export() {
        UserAdminUseCase.CsvExport export = userAdmin.exportUsers();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName())
                        .build()
                        .toString())
                .body(export.content());
    }

    /** The SEC-013 quarterly-recertification worksheet for the caller's bank. */
    @GetMapping("/api/admin/users/access-review")
    public ResponseEntity<?> accessReviewWorksheet() {
        return ResponseEntity.ok(accessReview.generate());
    }

    private static Role parseRole(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Unknown role '" + value + "'; use ADMIN, APPROVER, READ_WRITE or READ_ONLY.");
        }
    }
}
