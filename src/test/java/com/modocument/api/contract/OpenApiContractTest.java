package com.modocument.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.infrastructure.ratelimit.ApiRateLimitInterceptor;
import com.modocument.infrastructure.ratelimit.LlmQuotaInterceptor;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The machine-readable API contract (SEC-006, gate E2), validated in CI.
 * Fetches the springdoc-GENERATED spec from {@code /v3/api-docs} and asserts:
 * it is a well-formed OpenAPI 3.1 document titled/versioned from the build;
 * every handler route of the application is in it (and nothing else);
 * bearerAuth is the global scheme with only the login entry points (and
 * the public synthetic probe) anonymous — and every operation the spec marks secured really answers an
 * anonymous call with 401; every rate-limited route documents its 429 +
 * Retry-After; every
 * controller carries an explicit {@code @Tag}; operationIds are stable.
 *
 * <p><b>Route inventory pin.</b> The sorted METHOD+path inventory must equal
 * the committed {@code docs/api/openapi-routes.txt} byte for byte — an
 * endpoint appearing, disappearing or changing its method set fails the
 * build until the file is regenerated and the diff reviewed (the same
 * update-on-purpose contract as the role-matrix CSV). On a deliberate change:
 * <pre>mvn -Dtest=OpenApiContractTest -Dmodocument.contract.update=true test</pre>
 * rewrites the file (the failure message also prints the expected content);
 * then apply docs/api/versioning-policy.md (removals/renames are breaking).
 *
 * <p>The test profile serves the spec anonymously (permit-dev-paths); the
 * production exposure (bearer token required) is {@code ApiDocsExposureTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    static final Path ROUTES_FILE = Path.of("docs", "api", "openapi-routes.txt");

    /**
     * Anonymous by design — must match SecurityConfig's permitAll list: the
     * login entry points plus the ws18 synthetic uptime probe (deliberately
     * public, per-IP rate-limited, version + one db boolean only).
     */
    private static final Set<String> ANONYMOUS_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/mfa/verify",
            "/api/auth/providers",
            "/api/probe/synthetic",
            // ws26: the client authenticates inside the request.
            "/api/integration/oauth/token");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private ApiRateLimitInterceptor apiRateLimit;
    @Autowired
    private ObjectProvider<BuildProperties> buildProperties;

    private String specJson;
    private JsonNode spec;

    @BeforeEach
    void fetchSpec() throws Exception {
        specJson = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        spec = MAPPER.readTree(specJson);
    }

    @Test
    void specIsAWellFormedOpenApiDocumentVersionedFromTheBuild() throws Exception {
        assertThat(spec.path("openapi").asText()).startsWith("3.1");
        // Round-trips through swagger-core's own OpenAPI model.
        OpenAPI model = Json31.mapper().readValue(specJson, OpenAPI.class);
        assertThat(model.getPaths()).isNotEmpty();

        assertThat(spec.at("/info/title").asText()).isEqualTo("MoDocument");
        BuildProperties build = buildProperties.getIfAvailable();
        if (build != null) { // present whenever Maven built the module (build-info goal)
            assertThat(spec.at("/info/version").asText()).isEqualTo(build.getVersion());
        }
        assertThat(spec.at("/info/version").asText()).isNotBlank();
        assertThat(spec.at("/info/description").asText())
                .contains("docs/api/versioning-policy.md", "/api/v2", "6 months");
        assertThat(spec.path("servers").size()).isPositive();
        assertThat(spec.at("/servers/0/url").asText()).isNotBlank();
    }

    @Test
    void everyHandlerRouteAppearsInTheSpecAndNothingElse() {
        assertThat(specInventory())
                .as("springdoc spec vs. RequestMappingHandlerMapping")
                .containsExactlyElementsOf(handlerInventory());
    }

    @Test
    void routeInventoryMatchesTheCommittedContract() throws IOException {
        String rendered = renderInventory(specInventory());
        if (Boolean.getBoolean("modocument.contract.update")) {
            Files.createDirectories(ROUTES_FILE.getParent());
            Files.writeString(ROUTES_FILE, rendered, StandardCharsets.UTF_8);
        }
        assertThat(ROUTES_FILE)
                .as("docs/api/openapi-routes.txt must exist (see this test's javadoc)")
                .exists();
        String committed = Files.readString(ROUTES_FILE, StandardCharsets.UTF_8);
        assertThat(committed)
                .as("The API route inventory changed. If deliberate, regenerate "
                        + "docs/api/openapi-routes.txt (mvn -Dtest=OpenApiContractTest "
                        + "-Dmodocument.contract.update=true test), check "
                        + "docs/api/versioning-policy.md, and commit. Expected content:%n%s",
                        rendered)
                .isEqualTo(rendered);
    }

    @Test
    void bearerAuthIsTheGlobalSchemeAndOnlyTheLoginEntryPointsAreAnonymous() {
        JsonNode scheme = spec.at("/components/securitySchemes/bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");

        JsonNode global = spec.path("security");
        assertThat(global.size()).isEqualTo(1);
        assertThat(global.get(0).has("bearerAuth")).isTrue();

        Set<String> anonymous = new TreeSet<>();
        List<String> securedWithoutAuthErrors = new ArrayList<>();
        forEachOperation((method, path, op) -> {
            if (op.has("security")) {
                assertThat(op.path("security").isEmpty())
                        .as("%s %s overrides security with a non-empty requirement", method, path)
                        .isTrue();
                anonymous.add(path);
            } else if (!op.at("/responses/401").isObject() || !op.at("/responses/403").isObject()) {
                securedWithoutAuthErrors.add(method + " " + path);
            }
        });
        assertThat(anonymous).containsExactlyInAnyOrderElementsOf(ANONYMOUS_PATHS);
        assertThat(securedWithoutAuthErrors).as("secured operations must document 401/403")
                .isEmpty();
    }

    @Test
    void everySecuredOperationInTheSpecRefusesAnAnonymousCaller() throws Exception {
        // The documented contract and the enforced one agree (gate E2): each
        // operation the spec marks as bearerAuth answers 401 without a token.
        List<String> secured = new ArrayList<>();
        forEachOperation((method, path, op) -> {
            if (!op.has("security")) {
                secured.add(method + " " + path);
            }
        });
        List<String> notRefused = new ArrayList<>();
        for (String route : secured) {
            String method = route.substring(0, route.indexOf(' '));
            String uri = route.substring(route.indexOf(' ') + 1).replaceAll("\\{[^}]+}", "1");
            int status = mvc.perform(request(HttpMethod.valueOf(method), uri))
                    .andReturn().getResponse().getStatus();
            if (status != 401) {
                notRefused.add(route + " -> " + status);
            }
        }
        assertThat(secured).hasSizeGreaterThan(100);
        assertThat(notRefused).as("anonymous calls that were not refused with 401").isEmpty();
    }

    @Test
    void everyRateLimitedRouteDocumentsA429WithRetryAfter() {
        assertThat(spec.at("/components/responses/TooManyRequests/headers/Retry-After").isObject())
                .isTrue();

        Set<String> expected = new TreeSet<>(apiRateLimit.routes().keySet());
        PathPatternParser parser = new PathPatternParser();
        List<PathPattern> llm = LlmQuotaInterceptor.PATHS.stream().map(parser::parse).toList();
        Set<String> documented = new TreeSet<>();
        forEachOperation((method, path, op) -> {
            if (llm.stream().anyMatch(p -> p.matches(PathContainer.parsePath(path)))) {
                expected.add(method + " " + path);
            }
            // ws26: the machine API is metered per client (MachineApiInterceptor).
            if (path.startsWith("/api/integration/v1/")) {
                expected.add(method + " " + path);
            }
            if (op.at("/responses/429").isObject()) {
                assertThat(op.at("/responses/429/$ref").asText())
                        .isEqualTo("#/components/responses/TooManyRequests");
                documented.add(method + " " + path);
            }
        });
        // Anonymous routes with their own per-IP/account throttles.
        expected.addAll(Set.of(
                "POST /api/auth/login", "POST /api/auth/refresh", "POST /api/auth/mfa/verify",
                "GET /api/probe/synthetic", "POST /api/integration/oauth/token"));
        assertThat(documented).containsExactlyElementsOf(expected);
    }

    @Test
    void everyOperationHasOneExplicitTagAndAStableUniqueOperationId() {
        Set<String> ids = new HashSet<>();
        forEachOperation((method, path, op) -> {
            JsonNode tags = op.path("tags");
            assertThat(tags.size()).as("tags of %s %s", method, path).isEqualTo(1);
            // springdoc's fallback tag for an un-annotated controller is
            // "<kebab-name>-controller": every controller must carry @Tag.
            assertThat(tags.get(0).asText()).as("tag of %s %s", method, path)
                    .doesNotEndWith("-controller");
            String id = op.path("operationId").asText();
            assertThat(id).as("operationId of %s %s", method, path)
                    .matches("[a-z][A-Za-z0-9]+");
            assertThat(ids.add(id)).as("duplicate operationId %s", id).isTrue();
        });
    }

    // ------------------------------------------------------------------ helpers

    private interface OperationVisitor {
        void visit(String method, String path, JsonNode operation);
    }

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private void forEachOperation(OperationVisitor visitor) {
        spec.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(entry -> {
                    if (HTTP_METHODS.contains(entry.getKey())) {
                        visitor.visit(entry.getKey().toUpperCase(Locale.ROOT), path.getKey(),
                                entry.getValue());
                    }
                }));
    }

    /** "METHOD path" for every operation in the spec, sorted by path then method. */
    private List<String> specInventory() {
        List<String> routes = new ArrayList<>();
        forEachOperation((method, path, op) -> routes.add(method + " " + path));
        return sorted(routes);
    }

    /** The same inventory from the application's own handler mappings. */
    private List<String> handlerInventory() {
        Set<String> routes = new HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            // Application routes only: springdoc's own endpoints and Spring
            // Boot's /error are framework plumbing, not API.
            if (!entry.getValue().getBeanType().getName().startsWith("com.modocument.")) {
                continue;
            }
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            assertThat(methods).as("%s maps every HTTP method — declare one",
                    entry.getValue()).isNotEmpty();
            for (String pattern : entry.getKey().getPatternValues()) {
                for (RequestMethod method : methods) {
                    routes.add(method.name() + " " + pattern);
                }
            }
        }
        return sorted(routes);
    }

    private static List<String> sorted(java.util.Collection<String> routes) {
        return routes.stream()
                .distinct()
                .sorted(Comparator.comparing((String r) -> r.substring(r.indexOf(' ') + 1))
                        .thenComparing(r -> r.substring(0, r.indexOf(' '))))
                .toList();
    }

    static String renderInventory(List<String> routes) {
        StringBuilder out = new StringBuilder()
                .append("# MoDocument API v1 - route inventory (METHOD path), generated from the\n")
                .append("# springdoc spec at /v3/api-docs. Pinned in CI by OpenApiContractTest:\n")
                .append("# a route added, removed or re-methoded fails the build until this file\n")
                .append("# is regenerated and the diff reviewed against\n")
                .append("# docs/api/versioning-policy.md. Regenerate with:\n")
                .append("#   mvn -Dtest=OpenApiContractTest \\\n")
                .append("#       -Dmodocument.contract.update=true test\n")
                .append("# ").append(routes.size()).append(" operations.\n");
        routes.forEach(route -> out.append(route).append('\n'));
        return out.toString();
    }
}
