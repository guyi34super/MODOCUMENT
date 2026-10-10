package com.modocument.infrastructure.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

/**
 * Keeps MFA challenge tokens from working as access tokens (gate B3).
 * Registered on the main {@code NimbusJwtDecoder} next to the
 * {@link RevocationValidator}: any bearer token carrying
 * {@code mfa_challenge: true} is rejected for every route EXCEPT the two
 * enrolment endpoints an admin who is forced into enrolment legitimately
 * needs ({@code POST /api/auth/mfa/enroll} and {@code /activate} — those
 * handlers additionally refuse a challenge token once MFA is active).
 * Everything without the marker claim passes through untouched.
 *
 * <p>Fail closed: when the current request cannot be determined (no bound
 * request attributes — never the case on the servlet path, where Spring
 * Boot's RequestContextFilter runs before the security chain), the challenge
 * token is rejected outright.
 */
@Component
public class MfaChallengeScopeValidator implements OAuth2TokenValidator<Jwt> {

    /** The only routes a challenge token may authenticate (forced enrolment). */
    private static final Set<String> ENROLLMENT_PATHS =
            Set.of("/api/auth/mfa/enroll", "/api/auth/mfa/activate");

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!Boolean.TRUE.equals(token.getClaim(MfaChallengeTokens.CLAIM_MFA_CHALLENGE))) {
            return OAuth2TokenValidatorResult.success();
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes
                && ENROLLMENT_PATHS.contains(servletAttributes.getRequest().getRequestURI())) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                "An MFA challenge token is not an access token.", null));
    }
}
