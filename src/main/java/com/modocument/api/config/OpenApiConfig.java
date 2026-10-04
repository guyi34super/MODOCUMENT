package com.modocument.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * The generated OpenAPI contract (SEC-006, gate E2): springdoc derives paths,
 * parameters and schemas from the handler methods and Bean Validation
 * annotations, so the spec at {@code /v3/api-docs} cannot drift from the code
 * the way a hand-written file does. Spec GENERATION is on in every profile;
 * the Swagger UI remains a dev-only tool (enabled by the {@code local}
 * profile) and, outside dev, the spec endpoint itself sits behind the same
 * bearer authentication as the rest of the API (see {@code SecurityConfig}
 * {@code permit-dev-paths}).
 *
 * <p>{@code OpenApiContractTest} pins the route inventory derived from this
 * spec against {@code docs/api/openapi-routes.txt} in CI, so an endpoint
 * appearing, disappearing or changing method set is an explicit, reviewed
 * change.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";

    /**
     * Routes callable without a bearer token (the anonymous login entry
     * points). Everything else inherits the global bearerAuth requirement.
     * Keep in sync with {@code SecurityConfig} and the PUBLIC_ROUTES list in
     * {@code RoutePermissionCoverageTest}.
     */
    static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/mfa/verify",
            "/api/auth/providers");

    private static final String DESCRIPTION = """
            MoDocument REST API — generated from code at /v3/api-docs (the \
            authoritative, machine-readable contract; SEC-006).

            Authentication: every endpoint except the login entry points \
            (/api/auth/login, /api/auth/refresh, /api/auth/mfa/verify, \
            /api/auth/providers) requires an HTTP Bearer JWT. Authorization is \
            permission-checked per route and tenant-scoped per bank; \
            cross-bank access is refused. Expensive endpoints are per-user \
            rate-limited and answer 429 with a Retry-After header.

            Versioning & deprecation policy (docs/api/versioning-policy.md): \
            published paths are URL-stable; changes within this version are \
            additive only (new endpoints, new optional fields). A breaking \
            change ships under a new /api/v2 namespace. Deprecated endpoints \
            are marked in this spec and kept for at least 6 months before \
            removal.""";

    @Bean
    public OpenAPI moDocumentOpenApi(ObjectProvider<BuildProperties> buildProperties,
                                     @Value("${modocument.api.server-url:/}") String serverUrl,
                                     @Value("${modocument.api.server-description:This host}")
                                     String serverDescription) {
        // Title/version come from the pom via build-info.properties
        // (spring-boot-maven-plugin build-info goal); the fallback covers
        // bare-IDE runs without a Maven build.
        BuildProperties build = buildProperties.getIfAvailable();
        String title = build != null ? build.getName() : "MoDocument";
        String version = build != null ? build.getVersion() : "unversioned-dev";
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .version(version)
                        .description(DESCRIPTION))
                .servers(List.of(new Server().url(serverUrl).description(serverDescription)))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/auth/login "
                                        + "(or /api/auth/mfa/verify when MFA is on).")))
                // Global default: every operation requires bearerAuth ...
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    /**
     * ... except the anonymous login entry points, which get an explicit empty
     * security array (the OpenAPI way to override a global requirement).
     */
    @Bean
    public OpenApiCustomizer publicRoutesWithoutAuth() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (PUBLIC_PATHS.contains(path)) {
                    item.readOperations().forEach(op -> op.setSecurity(List.of()));
                }
            });
        };
    }
}
