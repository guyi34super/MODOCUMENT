package com.modocument.application.usecase;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ws26 one-time enrolment token: format, hashing, constant-time match,
 * no secret in toString — and the agent "update available" comparison.
 */
class AgentEnrolmentTokenTest {

    @Test
    void aGeneratedTokenParsesBackAndMatchesOnlyItsOwnHash() {
        AgentEnrolmentToken token = AgentEnrolmentToken.generate(42);
        assertThat(token.value()).matches("mda1\\.42\\.[A-Za-z0-9_-]{43}");

        AgentEnrolmentToken parsed = AgentEnrolmentToken.parse(token.value()).orElseThrow();
        assertThat(parsed.agentId()).isEqualTo(42);
        String hash = AgentEnrolmentToken.hash(token.secret());
        assertThat(hash).hasSize(64).isLowerCase();
        assertThat(parsed.matches(hash)).isTrue();

        AgentEnrolmentToken other = AgentEnrolmentToken.generate(42);
        assertThat(other.matches(hash)).isFalse();
        assertThat(parsed.matches(null)).isFalse();
        assertThat(AgentEnrolmentToken.generate(42).secret()).isNotEqualTo(token.secret());
    }

    @Test
    void anythingButTheExactFormatIsRefused() {
        String secret = "A".repeat(43);
        assertThat(AgentEnrolmentToken.parse("mda1.7." + secret)).isPresent();
        assertThat(AgentEnrolmentToken.parse(null)).isEmpty();
        assertThat(AgentEnrolmentToken.parse("")).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda2.7." + secret)).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda1.0." + secret)).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda1.-7." + secret)).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda1.7." + secret + "A")).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda1.7." + "A".repeat(42) + "=")).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda1.99999999999." + secret)).isEmpty();
        assertThat(AgentEnrolmentToken.parse("mda1.7." + secret + "\n' OR 1=1")).isEmpty();
    }

    @Test
    void toStringNeverPrintsTheSecret() {
        AgentEnrolmentToken token = AgentEnrolmentToken.generate(9);
        assertThat(token.toString()).doesNotContain(token.secret()).contains("agentId=9");
    }

    @Test
    void updateAvailableComparesReleaseNumbers() {
        assertThat(IntegrationAgentUseCase.isOlder("1.0.0", "1.1.0")).isTrue();
        assertThat(IntegrationAgentUseCase.isOlder("1.9.9", "2.0.0")).isTrue();
        assertThat(IntegrationAgentUseCase.isOlder("1.10.0", "1.9.0")).isFalse();
        assertThat(IntegrationAgentUseCase.isOlder("1.1.0", "1.1.0")).isFalse();
        assertThat(IntegrationAgentUseCase.isOlder("1.1.0-rc.1", "1.1.0")).isTrue();
        assertThat(IntegrationAgentUseCase.isOlder("1.2.0", "1.1.0")).isFalse();
        assertThat(IntegrationAgentUseCase.isOlder(null, "1.1.0")).isFalse();
        assertThat(IntegrationAgentUseCase.isOlder("dev", "1.1.0")).isFalse();
        assertThat(IntegrationAgentUseCase.isOlder("1.0.0", null)).isFalse();
    }
}
