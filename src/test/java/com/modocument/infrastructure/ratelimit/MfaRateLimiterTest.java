package com.modocument.infrastructure.ratelimit;

import com.modocument.domain.model.settings.SettingKeys;
import com.modocument.testsupport.InMemorySettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA finding OBS-04: MFA verify allowed only 5 attempts per minute per
 * ADDRESS, so a bank's staff behind one NAT locked each other out. The
 * shipped per-address limit is now a flood guard (30/min) while the
 * per-USER limit — the actual TOTP brute-force control — stays 5/min.
 */
class MfaRateLimiterTest {

    private static final String BANK_NAT = "198.51.100.7";

    /** The shipped configuration: application.yml / @Value defaults, nothing stored. */
    private static MfaRateLimiter shipped(InMemorySettings settings) {
        return new MfaRateLimiter(
                settings.reader().getIntForPlatform(SettingKeys.RATELIMIT_MFA_PER_IP),
                settings.reader().getIntForPlatform(SettingKeys.RATELIMIT_MFA_PER_USER),
                60_000, 50_000, settings.reader());
    }

    @Test
    void theShippedLimitsAreThirtyPerAddressAndFivePerUser() {
        MfaRateLimiter limiter = shipped(InMemorySettings.shipped());
        assertThat(limiter.perIpMax()).isEqualTo(30);
        assertThat(limiter.perUserMax()).isEqualTo(5);
    }

    @Test
    void colleaguesBehindOneNatAreNotLockedOutByEachOther() {
        MfaRateLimiter limiter = shipped(InMemorySettings.shipped());
        // Ten users of one bank, one shared address, each needing up to three
        // tries (a mistyped code, a code that rolled over) in the same minute.
        for (int user = 1; user <= 10; user++) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                limiter.checkIp(BANK_NAT);
                limiter.checkUser(String.valueOf(user));
            }
        }
        // The address flood guard still holds beyond that.
        assertThatThrownBy(() -> limiter.checkIp(BANK_NAT))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void theStrictPerUserLimitStillStopsGuessingFromManyAddresses() {
        MfaRateLimiter limiter = shipped(InMemorySettings.shipped());
        for (int attempt = 1; attempt <= 5; attempt++) {
            limiter.checkIp("203.0.113." + attempt);
            limiter.checkUser("42");
        }
        limiter.checkIp("203.0.113.99");
        assertThatThrownBy(() -> limiter.checkUser("42"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void thePlatformOwnerCanTuneBothWithinTheirLimits() {
        InMemorySettings settings = InMemorySettings.shipped()
                .platform(SettingKeys.RATELIMIT_MFA_PER_IP, "60")
                .platform(SettingKeys.RATELIMIT_MFA_PER_USER, "50");
        MfaRateLimiter limiter = shipped(InMemorySettings.shipped());
        MfaRateLimiter tuned = new MfaRateLimiter(30, 5, 60_000, 50_000, settings.reader());
        assertThat(limiter.perIpMax()).isEqualTo(30);
        assertThat(tuned.perIpMax()).isEqualTo(60);
        assertThat(tuned.perUserMax()).as("hard ceiling 20").isEqualTo(20);
    }
}
