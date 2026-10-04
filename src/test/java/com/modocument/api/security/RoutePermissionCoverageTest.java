package com.modocument.api.security;

import com.modocument.domain.model.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deny-by-default, verified: every mapped handler method in the application
 * must carry exactly one {@code @RequiresPermission} (on the method or its
 * controller class) or be on the explicit PUBLIC_ROUTES allowlist. A new
 * endpoint without a permission fails this test by name, so an unguarded
 * route can never ship silently.
 */
@SpringBootTest
@ActiveProfiles("test")
class RoutePermissionCoverageTest {

    /**
     * The only routes that may exist without a permission: anonymous auth
     * entry points, the framework error page, health probes, and the dev-only
     * API docs (test profile sets permit-dev-paths, which registers springdoc).
     */
    private static final Set<String> PUBLIC_ROUTES = Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            // Second phase of login (ws06): its credential is the MFA
            // challenge token in the body, rate-limited like login itself.
            "/api/auth/mfa/verify",
            // ws07 OIDC: SSO discovery for the anonymous login page (which
            // providers exist — ids + display names, nothing secret).
            "/api/auth/providers",
            "/error");

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/actuator",
            "/v3/api-docs",
            "/swagger",
            // ws07 OIDC handshake endpoints (/oauth2/authorization/*,
            // /login/oauth2/code/*): served by Spring Security's own filters,
            // not annotated handlers — listed for the record; they only exist
            // when modocument.oidc.providers is configured.
            "/oauth2/",
            "/login/oauth2/");

    /**
     * Permissions reserved for later workstreams — in the matrix, no routes
     * yet. USER_ADMIN left this list with ws09 (it now guards the
     * /api/admin/users routes).
     */
    private static final Set<Permission> RESERVED = EnumSet.of(
            Permission.CHANGE_APPROVE, Permission.DOC_RELEASE);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyRouteCarriesExactlyOnePermissionOrIsExplicitlyPublic() {
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();

            RequiresPermission onMethod = AnnotatedElementUtils
                    .findMergedAnnotation(handler.getMethod(), RequiresPermission.class);
            RequiresPermission onClass = AnnotatedElementUtils
                    .findMergedAnnotation(handler.getBeanType(), RequiresPermission.class);

            String route = info.getPatternValues() + " -> "
                    + handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();

            if (isPublic(info)) {
                if (onMethod != null || onClass != null) {
                    offenders.add("PUBLIC route must not carry @RequiresPermission: " + route);
                }
                continue;
            }
            if (onMethod == null && onClass == null) {
                offenders.add("No @RequiresPermission (method or class): " + route);
            } else if (onMethod != null && onClass != null) {
                offenders.add("Both method- and class-level @RequiresPermission (ambiguous — "
                        + "keep exactly one): " + route);
            }
        }

        assertThat(offenders)
                .as("Every handler needs exactly one @RequiresPermission or a PUBLIC_ROUTES entry")
                .isEmpty();
    }

    @Test
    void everyNonReservedPermissionGuardsAtLeastOneRoute() {
        Set<Permission> used = EnumSet.noneOf(Permission.class);
        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            RequiresPermission onMethod = AnnotatedElementUtils
                    .findMergedAnnotation(handler.getMethod(), RequiresPermission.class);
            RequiresPermission onClass = AnnotatedElementUtils
                    .findMergedAnnotation(handler.getBeanType(), RequiresPermission.class);
            if (onMethod != null) {
                used.add(onMethod.value());
            } else if (onClass != null) {
                used.add(onClass.value());
            }
        }

        EnumSet<Permission> expected = EnumSet.complementOf(EnumSet.copyOf(RESERVED));
        assertThat(used)
                .as("Every permission except the reserved ones (%s) must guard >= 1 route; "
                        + "an unused permission is dead vocabulary", RESERVED)
                .containsExactlyInAnyOrderElementsOf(expected);

        assertThat(used)
                .as("Reserved permissions (%s) must not guard routes yet — their workstreams "
                        + "have not landed", RESERVED)
                .doesNotContainAnyElementsOf(RESERVED);
    }

    private static boolean isPublic(RequestMappingInfo info) {
        Set<String> patterns = new TreeSet<>(info.getPatternValues());
        if (patterns.isEmpty()) {
            return false;
        }
        // Every pattern of the mapping must be public for the mapping to count
        // as public — a mixed mapping stays guarded.
        return patterns.stream().allMatch(pattern ->
                PUBLIC_ROUTES.contains(pattern)
                        || PUBLIC_PREFIXES.stream().anyMatch(pattern::startsWith));
    }
}
