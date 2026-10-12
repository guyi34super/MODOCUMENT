package com.modocument.api.config;

import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.ratelimit.LlmQuotaInterceptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.util.StringUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The generated OpenAPI contract (SEC-006, gate E2): springdoc derives paths,
 * parameters and schemas from the handler methods and Bean Validation
 * annotations, so the spec at {@code /v3/api-docs} cannot drift from the code
 * the way the hand-written {@code static/swagger/spec.yaml} did. Spec
 * GENERATION is on in every profile; the Swagger UI remains a dev-only tool
 * (enabled by the {@code local} profile).
 *
 * <p><b>Exposure.</b> Outside dev ({@code permit-dev-paths} off) the spec
 * endpoint sits behind the same bearer authentication as every non-login
 * route ({@code SecurityConfig}'s {@code anyRequest().authenticated()}); it
 * carries no {@code @RequiresPermission} because it is framework-served and
 * describes routes, not data — see docs/api/versioning-policy.md for the
 * reasoning and {@code ApiDocsExposureTest} for the proof.
 *
 * <p><b>What the customizers add.</b> A global bearerAuth requirement with the
 * anonymous login entry points overridden to {@code security: []}; 401/403 on
 * every secured operation; 429 + Retry-After on every rate-limited one (the
 * {@link ApiRateLimitInterceptor} table, the LLM quota paths and the
 * anonymous auth throttles); stable operationIds ({@code <controller><Method>})
 * instead of springdoc's order-dependent {@code create_9}.
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
     * points, plus the ws18 synthetic uptime probe — an external monitor
     * must not hold credentials). Everything else inherits the global
     * bearerAuth requirement. Mirrors {@code SecurityConfig}'s permitAll
     * list; the PUBLIC_ROUTES allowlist in {@code RoutePermissionCoverageTest}
     * and {@code OpenApiContractTest} hold both to it.
     */
    static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/mfa/verify",
            "/api/auth/providers",
            "/api/probe/synthetic",
            // ws26: OAuth2 client credentials — the client authenticates in
            // the request (Basic, form secret or client certificate).
            "/api/integration/oauth/token",
            // ws26: Connector Agent enrolment — the one-time token in the body
            // is the credential (AgentEnrolmentRateLimiter, per IP).
            "/api/agent/v1/enroll");

    /** ws26 machine API: every operation is metered per client (MachineApiInterceptor). */
    static final String MACHINE_API_PREFIX = "/api/integration/v1/";

    /**
     * Anonymous routes with their own per-IP/account throttle
     * (AuthRateLimiter, MfaRateLimiter, the probe's own per-IP limiter in
     * SyntheticProbeController — none of them in ApiRateLimitInterceptor's
     * per-user table, so nothing is counted twice).
     */
    static final Set<String> THROTTLED_PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/mfa/verify",
            "/api/probe/synthetic",
            "/api/integration/oauth/token",
            "/api/agent/v1/enroll");

    private static final String UNAUTHORIZED = "Unauthorized";
    private static final String FORBIDDEN = "Forbidden";
    private static final String TOO_MANY_REQUESTS = "TooManyRequests";
    private static final String ERROR_BODY = "ErrorBody";

    private static final String DESCRIPTION = """
            MoDocument REST API, version 1 (the unprefixed /api, /Templates, \
            ... namespace). Generated from the code at /v3/api-docs: this is \
            the authoritative, machine-readable contract (SEC-006); the \
            hand-written /swagger/spec.yaml is deprecated.

            Authentication: every endpoint except the login entry points \
            (/api/auth/login, /api/auth/refresh, /api/auth/mfa/verify, \
            /api/auth/providers) and the public synthetic uptime probe \
            (/api/probe/synthetic, rate-limited per IP) requires an HTTP \
            Bearer JWT (401 without). \
            Authorization is permission-checked per route (403) and \
            tenant-scoped per bank: another bank's resources answer 404, \
            never data. Expensive endpoints are rate-limited per user and \
            answer 429 with a Retry-After header (seconds).

            Versioning & deprecation policy (docs/api/versioning-policy.md): \
            published paths are URL-stable; changes within this version are \
            additive only (new endpoints, new optional fields, new response \
            fields). A breaking change ships under a new /api/v2 namespace \
            alongside v1. Deprecated operations are flagged deprecated: true \
            in this spec and kept for at least 6 months before removal.

            Integration API (/api/integration/v1, for bank systems): callable \
            ONLY by API clients — an OAuth2 client-credentials token from \
            POST /api/integration/oauth/token (sent as Bearer), an \
            "Authorization: ApiKey <prefix>.<secret>" header, or a mutual-TLS \
            client certificate; user tokens are refused there, and client \
            credentials are refused everywhere else. POSTs require an \
            Idempotency-Key header. Guide: docs/integration/developer-guide.md.""";

    @Bean
    public OpenAPI moDocumentOpenApi(ObjectProvider<BuildProperties> buildProperties,
                                     @Value("${modocument.api.server-url:/}") String serverUrl,
                                     @Value("${modocument.api.server-description:This deployment}")
                                     String serverDescription) {
        // Title/version come from the pom via build-info.properties
        // (spring-boot-maven-plugin build-info goal); the fallback covers
        // bare-IDE runs without a Maven build.
        BuildProperties build = buildProperties.getIfAvailable();
        String title = build != null && build.getName() != null ? build.getName() : "MoDocument";
        String version = build != null && build.getVersion() != null
                ? build.getVersion() : "unversioned-dev";
        Content errorJson = new Content().addMediaType("application/json",
                new MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>()
                        .$ref("#/components/schemas/" + ERROR_BODY)));
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .version(version)
                        .description(DESCRIPTION))
                .servers(List.of(new Server().url(serverUrl).description(serverDescription)))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/auth/login "
                                        + "(or /api/auth/mfa/verify when MFA is on); "
                                        + "15-minute lifetime, renewed via /api/auth/refresh."))
                        .addSchemas(ERROR_BODY, new ObjectSchema()
                                .addProperty("error", new StringSchema()
                                        .description("Message safe to show the user."))
                                .addProperty("requestId", new StringSchema()
                                        .description("Correlates with the server logs (E9).")))
                        .addResponses(UNAUTHORIZED, new ApiResponse()
                                .description("Missing, expired, revoked or invalid bearer token."))
                        .addResponses(FORBIDDEN, new ApiResponse()
                                .description("Authenticated, but the caller's role lacks this "
                                        + "route's permission (or a password change is pending).")
                                .content(errorJson))
                        .addResponses(TOO_MANY_REQUESTS, new ApiResponse()
                                .description("Per-user (or, on the login routes and the "
                                        + "public probe, per-IP/account) rate limit reached "
                                        + "for the current window.")
                                .addHeaderObject("Retry-After", new Header()
                                        .description("Seconds until the window resets.")
                                        .schema(new IntegerSchema().minimum(
                                                java.math.BigDecimal.ONE)))
                                .content(errorJson)))
                // Global default: every operation requires bearerAuth (the
                // public ones are overridden in contractCustomizer).
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    /**
     * Per-operation contract details that need the final path table: the
     * anonymous override (an explicit empty security array is the OpenAPI way
     * to lift a global requirement), the 401/403 every secured route can
     * answer, and the 429 on every rate-limited route.
     */
    @Bean
    public OpenApiCustomizer contractCustomizer(ApiRateLimitInterceptor apiRateLimit) {
        PathPatternParser parser = new PathPatternParser();
        List<PathPattern> llmPatterns = LlmQuotaInterceptor.PATHS.stream()
                .map(parser::parse)
                .toList();
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                boolean isPublic = PUBLIC_PATHS.contains(path);
                for (Map.Entry<PathItem.HttpMethod, Operation> entry
                        : item.readOperationsMap().entrySet()) {
                    Operation op = entry.getValue();
                    if (op.getResponses() == null) {
                        op.setResponses(new ApiResponses());
                    }
                    if (isPublic) {
                        op.setSecurity(List.of());
                    } else {
                        addRef(op, "401", UNAUTHORIZED);
                        addRef(op, "403", FORBIDDEN);
                    }
                    boolean throttled = THROTTLED_PUBLIC_PATHS.contains(path)
                            || path.startsWith(MACHINE_API_PREFIX)
                            || apiRateLimit.bucketFor(entry.getKey().name(), path) != null
                            || llmPatterns.stream().anyMatch(p ->
                                    p.matches(PathContainer.parsePath(path)));
                    if (throttled) {
                        addRef(op, "429", TOO_MANY_REQUESTS);
                    }
                }
            });
        };
    }

    /**
     * Stable operationIds: {@code <controller stem><MethodName>}, e.g.
     * {@code ingestionJobCreateFromUpload}. springdoc's default is the bare
     * method name with an order-dependent {@code _N} suffix on clashes
     * ({@code create_9}), which renumbers whenever a controller is added — a
     * silent break for generated clients. An explicit
     * {@code @Operation(operationId = ...)} still wins.
     */
    @Bean
    public OperationCustomizer stableOperationIds() {
        return (operation, handlerMethod) -> {
            io.swagger.v3.oas.annotations.Operation annotation = handlerMethod
                    .getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
            if (annotation != null && StringUtils.hasText(annotation.operationId())) {
                return operation;
            }
            String stem = handlerMethod.getBeanType().getSimpleName()
                    .replaceFirst("Controller$", "");
            operation.setOperationId(StringUtils.uncapitalize(stem)
                    + StringUtils.capitalize(handlerMethod.getMethod().getName()));
            return operation;
        };
    }

    /**
     * ws28 (contracts §8): a {@link DeprecatedEndpoint} handler is
     * {@code deprecated: true}, its description names the replacement and the
     * sunset (docs/api/versioning-policy.md §4).
     */
    @Bean
    public OperationCustomizer deprecatedEndpoints() {
        return (operation, handlerMethod) -> {
            DeprecatedEndpoint deprecated = DeprecationHeaderInterceptor.find(handlerMethod);
            if (deprecated == null) {
                return operation;
            }
            operation.setDeprecated(true);
            String note = "Deprecated since " + deprecated.since()
                    + (deprecated.replacement().isBlank() ? "" : "; use " + deprecated.replacement())
                    + "; removal not before " + deprecated.sunset()
                    + " (responses carry Deprecation/Sunset headers).";
            operation.setDescription(operation.getDescription() == null ? note
                    : operation.getDescription() + "\n\n" + note);
            return operation;
        };
    }

    private static void addRef(Operation op, String status, String component) {
        if (!op.getResponses().containsKey(status)) {
            op.getResponses().addApiResponse(status,
                    new ApiResponse().$ref("#/components/responses/" + component));
        }
    }
}
