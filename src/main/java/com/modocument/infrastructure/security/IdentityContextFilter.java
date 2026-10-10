package com.modocument.infrastructure.security;

import com.modocument.application.port.TenantContext;
import com.modocument.infrastructure.security.client.ClientAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * THE single place a request's identity becomes ambient state (formerly
 * TenantContextFilter; renamed when it took on the logging identity too).
 * From the validated JWT it
 * <ul>
 *   <li>opens {@link TenantContext} with the bank_id claim — which also
 *       mirrors the tenant onto the {@code bankId} MDC key (TenantContext
 *       owns that key, so system/startup threads log {@code bankId=*}
 *       consistently), and</li>
 *   <li>puts {@code userId} (the subject) and {@code role} on the MDC, so
 *       with {@link RequestIdFilter}'s {@code requestId} every log line of
 *       an authenticated request says who, which bank and which request
 *       (E9).</li>
 * </ul>
 * A machine caller (ws26 {@link ClientAuthentication}: client token, API key
 * or client certificate) opens its ApiClient's bank the same way and logs as
 * {@code userId=client:<apiClientId>}, {@code role=API_CLIENT}.
 * Added to the security chain AFTER bearer authentication (not
 * auto-registered as a servlet filter — SecurityConfig places it
 * explicitly), and everything is cleared in a finally block so pooled
 * threads never leak a tenant or identity.
 */
public class IdentityContextFilter extends OncePerRequestFilter {

    public static final String MDC_USER_ID = "userId";
    public static final String MDC_ROLE = "role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean tenantOpened = false;
        boolean identityPut = false;
        if (authentication instanceof ClientAuthentication client) {
            // ws26 machine caller (client token, API key or certificate):
            // the client's own bank, logged as "client:<id>" — never a user.
            TenantContext.open(client.getPrincipal().bankId());
            tenantOpened = true;
            MDC.put(MDC_USER_ID, "client:" + client.getPrincipal().apiClientId());
            MDC.put(MDC_ROLE, "API_CLIENT");
            identityPut = true;
        } else if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            if (jwt.getClaims().get(JwtService.CLAIM_BANK_ID) instanceof Number bankId) {
                TenantContext.open(bankId.intValue()); // also sets MDC bankId
                tenantOpened = true;
            }
            MDC.put(MDC_USER_ID, jwt.getSubject());
            if (jwt.getClaims().get(JwtService.CLAIM_ROLES) instanceof List<?> roles
                    && !roles.isEmpty()) {
                MDC.put(MDC_ROLE, String.valueOf(roles.get(0)));
            }
            identityPut = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (tenantOpened) {
                TenantContext.clear(); // also removes MDC bankId
            }
            if (identityPut) {
                MDC.remove(MDC_USER_ID);
                MDC.remove(MDC_ROLE);
            }
        }
    }
}
