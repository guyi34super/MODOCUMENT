package com.modocument.api.security;

import com.modocument.application.port.AuditRecorder;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Turns every method-security refusal into an append-only audit row (C7):
 * {@code MethodSecurityConfig} wires the {@code AuthorizationEventPublisher}
 * so a failed {@code @RequiresPermission} check publishes an
 * {@link AuthorizationDeniedEvent} (synchronously, on the request thread),
 * and this listener records AUTHORIZATION_DENIED with the request path as
 * the entity id. Actor, bank, session jti, request id and ip are enriched
 * by the recorder from the request's identity/MDC context; the DENIED
 * outcome writes in its own transaction, so the row survives the 403 path.
 */
@Component
public class AuthorizationDeniedAuditListener {

    private final AuditRecorder audit;

    public AuthorizationDeniedAuditListener(AuditRecorder audit) {
        this.audit = audit;
    }

    @EventListener
    public void onAuthorizationDenied(AuthorizationDeniedEvent<?> event) {
        audit.denied("AUTHORIZATION_DENIED", "Route", currentRequestPath());
    }

    /** The denied request's path; a non-request denial records the marker. */
    private static String currentRequestPath() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRequestURI();
        }
        return "<non-request>";
    }
}
