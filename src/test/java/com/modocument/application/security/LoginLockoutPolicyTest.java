package com.modocument.application.security;

import com.modocument.domain.entity.AppUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate B4: the lockout arithmetic — 5 allowed, 6th locks, backoff doubles. */
class LoginLockoutPolicyTest {

    private final LoginLockoutPolicy policy = new LoginLockoutPolicy(5, 1, 30);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 3, 12, 0);

    @Test
    void fiveFailuresOnlyCountSixthLocksForOneMinute() {
        AppUser user = new AppUser();
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(policy.registerFailure(user, now)).as("attempt %d", attempt).isFalse();
            assertThat(user.getLockedUntil()).isNull();
        }
        assertThat(user.getFailedLoginCount()).isEqualTo(5);

        assertThat(policy.registerFailure(user, now)).isTrue();

        assertThat(user.getLockedUntil()).isEqualTo(now.plusMinutes(1));
        assertThat(user.getLockoutCount()).isEqualTo(1);
        // A fresh run of failures is needed before the next (longer) lock.
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(policy.isLocked(user, now)).isTrue();
        assertThat(policy.isLocked(user, now.plusMinutes(1))).isFalse();
    }

    @Test
    void backoffDoublesPerLockoutAndCapsAtThirtyMinutes() {
        assertThat(policy.backoffMinutes(0)).isEqualTo(1);
        assertThat(policy.backoffMinutes(1)).isEqualTo(2);
        assertThat(policy.backoffMinutes(2)).isEqualTo(4);
        assertThat(policy.backoffMinutes(4)).isEqualTo(16);
        assertThat(policy.backoffMinutes(5)).isEqualTo(30); // 32 capped
        assertThat(policy.backoffMinutes(20)).isEqualTo(30);
    }

    @Test
    void secondLockoutLastsTwoMinutes() {
        AppUser user = new AppUser();
        user.setLockoutCount(1); // already locked once since the last success

        for (int attempt = 1; attempt <= 5; attempt++) {
            policy.registerFailure(user, now);
        }
        assertThat(policy.registerFailure(user, now)).isTrue();

        assertThat(user.getLockedUntil()).isEqualTo(now.plusMinutes(2));
        assertThat(user.getLockoutCount()).isEqualTo(2);
    }

    @Test
    void successResetsEverything() {
        AppUser user = new AppUser();
        user.setFailedLoginCount(4);
        user.setLockoutCount(3);
        user.setLockedUntil(now.minusMinutes(1)); // expired lock

        policy.registerSuccess(user);

        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockoutCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }
}
