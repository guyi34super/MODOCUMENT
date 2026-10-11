package com.modocument.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request to create an API client (ws26, contracts §7.3) — submitted as an
 * API_CLIENT_CREATE maker/checker change. {@code expiresAt} is ISO-8601
 * (null = the default lifetime); {@code certificatePem} is required for MTLS
 * and refused otherwise. {@code comment} is the maker's note to the checker.
 */
public record ApiClientRequest(@NotBlank @Size(max = 100) String name,
                               @NotBlank @Size(max = 40) String authMethod,
                               @Size(max = 10) List<@NotBlank @Size(max = 40) String> scopes,
                               @Size(max = 20) List<@NotBlank @Size(max = 64) String> allowedCidrs,
                               @Size(max = 40) String expiresAt,
                               @Size(max = 20_000) String certificatePem,
                               @Size(max = 500) String comment) {
}
