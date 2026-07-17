package com.modocument.infrastructure.security;

import com.modocument.domain.entity.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Issues HS256-signed JWTs for authenticated users. */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlHours;

    public JwtService(JwtEncoder encoder,
                      @Value("${modocument.security.jwt.ttl-hours:24}") long ttlHours) {
        this.encoder = encoder;
        this.ttlHours = ttlHours;
    }

    public String issueToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.getAppUserId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .claim("email", user.getEmail())
                .claim("displayName", user.getDisplayName() == null ? "" : user.getDisplayName())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
