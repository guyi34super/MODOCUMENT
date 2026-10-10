package com.modocument.api.controller;

import com.modocument.api.security.RequiresPermission;
import com.modocument.application.dto.ApiClientRequest;
import com.modocument.application.dto.ApiClientSecretView;
import com.modocument.application.dto.ApiClientView;
import com.modocument.application.usecase.ApiClientAdminUseCase;
import com.modocument.domain.model.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API clients of the caller's bank (ws26, contracts §7.3), INTEGRATION_ADMIN:
 * <ul>
 *   <li>{@code GET} — the bank's clients plus pending create requests;</li>
 *   <li>{@code POST} — 202 + the API_CLIENT_CREATE pending change;</li>
 *   <li>{@code GET {id}/initial-secret} — the maker, once, within 24 h of
 *       approval: {@code {clientId, secret}};</li>
 *   <li>{@code POST {id}/rotate} — 200 {@code {clientId, secret}} (shown
 *       once; the previous secret works for 24 h more);</li>
 *   <li>{@code POST {id}/revoke} — 200, immediate.</li>
 * </ul>
 * Secret-bearing answers are {@code Cache-Control: no-store}.
 */
@RestController
@Tag(name = "Integration API Clients", description = "Machine identities of the bank's systems")
@RequiresPermission(Permission.INTEGRATION_ADMIN)
public class ApiClientAdminController {

    private static final String BASE = "/api/admin/integrations/api-clients";

    private final ApiClientAdminUseCase useCase;

    public ApiClientAdminController(ApiClientAdminUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping(BASE)
    public List<ApiClientView> list() {
        return useCase.list();
    }

    @PostMapping(BASE)
    public ResponseEntity<?> create(@Valid @RequestBody ApiClientRequest request) {
        return ResponseEntity.accepted().body(useCase.requestCreate(request));
    }

    @GetMapping(BASE + "/{id}/initial-secret")
    public ResponseEntity<ApiClientSecretView> initialSecret(@PathVariable int id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(useCase.readInitialSecret(id));
    }

    @PostMapping(BASE + "/{id}/rotate")
    public ResponseEntity<ApiClientSecretView> rotate(@PathVariable int id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(useCase.rotate(id));
    }

    @PostMapping(BASE + "/{id}/revoke")
    public ApiClientView revoke(@PathVariable int id) {
        return useCase.revoke(id);
    }
}
