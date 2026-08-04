package com.modocument.infrastructure.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF guard must refuse any host that resolves to a non-routable address —
 * the exact primitive the review's proof-of-concept abused. IP literals are
 * used so the test never touches DNS.
 */
class OutboundHostPolicyTest {

    private final OutboundHostPolicy policy = OutboundHostPolicy.enforcing();

    @Test
    void refusesLoopbackPrivateLinkLocalAndMetadata() {
        assertThat(policy.isHostAllowed("127.0.0.1")).isFalse();
        assertThat(policy.isHostAllowed("10.1.2.3")).isFalse();
        assertThat(policy.isHostAllowed("192.168.0.5")).isFalse();
        assertThat(policy.isHostAllowed("172.16.9.9")).isFalse();
        assertThat(policy.isHostAllowed("169.254.169.254")).isFalse(); // cloud metadata
        assertThat(policy.isHostAllowed("100.64.0.1")).isFalse();      // carrier-grade NAT
        assertThat(policy.isHostAllowed("::1")).isFalse();
        assertThat(policy.isHostAllowed("fd00::1")).isFalse();         // unique-local IPv6
    }

    @Test
    void allowsAPublicAddress() {
        assertThat(policy.isHostAllowed("8.8.8.8")).isTrue();
        assertThat(policy.isHostAllowed("1.1.1.1")).isTrue();
    }

    @Test
    void checkUrlThrowsForAnInternalTarget() {
        assertThatThrownBy(() -> policy.checkUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.checkUrl("http://127.0.0.1:8080/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkJdbcUrlBlocksInternalHostButPassesHostlessUrls() {
        assertThatThrownBy(() -> policy.checkJdbcUrl("jdbc:postgresql://127.0.0.1:5432/app"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.checkJdbcUrl("jdbc:postgresql://10.0.0.9:5432/app"))
                .isInstanceOf(IllegalArgumentException.class);
        // An in-memory/embedded URL has no network host to reach — allowed.
        policy.checkJdbcUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    }

    @Test
    void disabledPolicyAllowsEverything() {
        OutboundHostPolicy off = OutboundHostPolicy.disabled();
        assertThat(off.isHostAllowed("127.0.0.1")).isTrue();
        off.checkUrl("http://127.0.0.1:8080/");
        off.checkJdbcUrl("jdbc:postgresql://127.0.0.1:5432/app");
    }
}
