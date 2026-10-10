package com.modocument.api.config;

import com.modocument.api.controller.MachineApi;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.port.integration.CurrentClientProvider;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.infrastructure.ratelimit.FixedWindowRateLimiter;
import com.modocument.infrastructure.ratelimit.TooManyRequestsException;
import com.modocument.infrastructure.security.client.ApiClientAuthenticator;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-cutting duties of every machine-API call (ws26), for handlers of a
 * {@link MachineApi} controller:
 * <ul>
 *   <li>a per-client fixed window, bucket {@code client:<apiClientId>}
 *       ({@code modocument.integration.api.per-client-max} per
 *       {@code window}; 429 + Retry-After) — in preHandle, i.e. before the
 *       request body is even read;</li>
 *   <li>the client's LastUsedAt (at most once a minute);</li>
 *   <li>one {@code INTEGRATION_API_CALL} audit row per call with the client
 *       as actor ({@code api-client:<clientId>}, role API_CLIENT), the
 *       method, the matched route, the status and the outcome.</li>
 * </ul>
 * Selected by the handler's controller annotation, not by path, so URL
 * casing cannot dodge it (same reasoning as ApiRateLimitInterceptor).
 */
@Component
public class MachineApiInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MachineApiInterceptor.class);

    public static final String API_CALL = "INTEGRATION_API_CALL";

    private final CurrentClientProvider clients;
    private final ApiClientAuthenticator authenticator;
    private final AuditRecorder audit;
    private final FixedWindowRateLimiter perClient;

    public MachineApiInterceptor(CurrentClientProvider clients,
                                 ApiClientAuthenticator authenticator,
                                 AuditRecorder audit,
                                 IntegrationClientProperties properties) {
        this.clients = clients;
        this.authenticator = authenticator;
        this.audit = audit;
        this.perClient = new FixedWindowRateLimiter(
                Math.max(1, properties.getApi().getPerClientMax()),
                Math.max(1, properties.getApi().getWindow().toMillis()), 10_000);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        Optional<ClientPrincipal> client = machineCall(handler);
        if (client.isEmpty()) {
            return true;
        }
        String key = "client:" + client.get().apiClientId();
        long now = System.currentTimeMillis();
        if (!perClient.tryAcquire(key, now)) {
            throw new TooManyRequestsException(
                    "This API client has reached its request limit for now. Try again later.",
                    perClient.retryAfterSeconds(key, now));
        }
        authenticator.touchLastUsed(client.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Optional<ClientPrincipal> client = machineCall(handler);
        if (client.isEmpty()) {
            return;
        }
        int status = ex instanceof AccessDeniedException ? 403 : response.getStatus();
        AuditOutcome outcome = status == 401 || status == 403 ? AuditOutcome.DENIED
                : status >= 400 || ex != null ? AuditOutcome.FAILED : AuditOutcome.SUCCESS;
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("method", request.getMethod());
        values.put("route", pattern == null ? request.getRequestURI() : pattern.toString());
        values.put("status", status);
        values.put("authMethod", client.get().authMethod().name());
        try {
            audit.record(AuditEventSpec.builder(API_CALL, outcome)
                    .entity("ApiClient", String.valueOf(client.get().apiClientId()))
                    .actorEmail("api-client:" + client.get().clientId())
                    .actorRole("API_CLIENT")
                    .bankId(client.get().bankId())
                    .newValues(values)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Could not audit an integration API call: {}", e.getMessage());
        }
    }

    private Optional<ClientPrincipal> machineCall(Object handler) {
        if (handler instanceof HandlerMethod method && AnnotatedElementUtils.hasAnnotation(
                method.getBeanType(), MachineApi.class)) {
            return clients.current();
        }
        return Optional.empty();
    }
}
