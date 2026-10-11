package com.modocument.infrastructure.security;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.model.settings.SettingKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Issues HS256-signed access tokens. Claims are deliberately lean: sub (user
 * id), jti (random, for the revocation denylist), roles (single application
 * role), bank_id (tenant) and token_ver (compared against
 * AppUsers.TokenVersion by the RevocationValidator). Profile data such as
 * email/display name is NOT in the token — the login response body carries it.
 *
 * <p>Access tokens are short-lived (default 15 minutes); the refresh-token
 * rotation flow in AuthUseCase keeps sessions alive. The lifetime is the
 * PLATFORM setting {@code security.jwt.ttlMinutes} (5–15), read per token
 * (with nothing stored: {@code modocument.security.jwt.ttl-minutes}). An
 * explicitly configured legacy {@code ttl-hours} still wins — that
 * deployment pinned its lifetime outside the settings (and outside the
 * limits, which the settings startup check reports).
 */
@Service
public class JwtService {

    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_BANK_ID = "bank_id";
    public static final String CLAIM_TOKEN_VERSION = "token_ver";

    private final JwtEncoder encoder;
    private final long ttlMinutes;
    private final boolean legacyPinned;
    /** Null = the fixed yml lifetime (unit-test wiring). */
    private final SettingsReader settings;

    public JwtService(JwtEncoder encoder, long ttlMinutes, long legacyTtlHours) {
        this(encoder, ttlMinutes, legacyTtlHours, null);
    }

    @Autowired
    public JwtService(JwtEncoder encoder,
                      @Value("${modocument.security.jwt.ttl-minutes:15}") long ttlMinutes,
                      // Pre-rotation deployments configured hours; honor an
                      // explicitly-set legacy value so they keep booting.
                      @Value("${modocument.security.jwt.ttl-hours:0}") long legacyTtlHours,
                      SettingsReader settings) {
        this.encoder = encoder;
        this.legacyPinned = legacyTtlHours > 0;
        this.ttlMinutes = legacyPinned ? legacyTtlHours * 60 : ttlMinutes;
        this.settings = settings;
    }

    /** The access-token lifetime in force now, in minutes. */
    public long ttlMinutes() {
        return settings == null || legacyPinned ? ttlMinutes
                : settings.getIntForPlatform(SettingKeys.JWT_TTL_MINUTES);
    }

    public String issueToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getAppUserId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttlMinutes(), ChronoUnit.MINUTES))
                .claim(CLAIM_ROLES, List.of(user.getRole().name()))
                .claim(CLAIM_BANK_ID, user.getBankId())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
