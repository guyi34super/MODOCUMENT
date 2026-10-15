package com.modocument.doccreation.api;

import com.modocument.api.security.RequiresPermission;
import com.modocument.doccreation.usecase.DocLegacyPurgeUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one-off purge of the retired Word path (contract §5 Admin): deletes the
 * caller's bank's Word-based templates with their documents, content and
 * stored files; audited DOC_LEGACY_PURGED. Bank administrators only.
 */
@RestController
@Tag(name = "Doc Creation: Admin", description = "Purge of the retired Word templates")
@RequiresPermission(Permission.USER_ADMIN)
public class DocLegacyPurgeController {

    private final DocLegacyPurgeUseCase useCase;

    public DocLegacyPurgeController(DocLegacyPurgeUseCase useCase) {
        this.useCase = useCase;
    }

    /** {@code {blueprints, instances, files}}: what was deleted in the caller's bank. */
    @PostMapping("/api/admin/doccreation/purge-legacy")
    public ResponseEntity<?> purgeLegacy() {
        return ResponseEntity.ok(useCase.purgeLegacy());
    }
}
