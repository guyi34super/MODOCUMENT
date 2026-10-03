package com.modocument.infrastructure.security;

import com.modocument.application.port.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates {@link TenantContext} with the caller's bank for the duration of
 * the request, read from the validated JWT's bank_id claim. Added to the
 * security chain AFTER bearer authentication (not auto-registered as a
 * servlet filter — SecurityConfig places it explicitly), and always cleared
 * in a finally block so pooled threads never leak a tenant.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean opened = false;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getClaims().get(JwtService.CLAIM_BANK_ID) instanceof Number bankId) {
            TenantContext.open(bankId.intValue());
            opened = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (opened) {
                TenantContext.clear();
            }
        }
    }
}
