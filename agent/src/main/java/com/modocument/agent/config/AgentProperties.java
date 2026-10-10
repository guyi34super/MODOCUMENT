package com.modocument.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Agent configuration ({@code modocument.agent.*}, mapped from environment
 * variables in application.yml — see agent/README.md):
 *
 * @param url              MoDocument base URL (MODOCUMENT_URL), https unless
 *                         {@code allowInsecureHttp}
 * @param enrolmentToken   one-time token, needed on the first start only
 *                         (MODOCUMENT_ENROLMENT_TOKEN)
 * @param name             reported at enrolment (MODOCUMENT_AGENT_NAME, default host name)
 * @param stateDir         issued credentials + health file (MODOCUMENT_AGENT_STATE_DIR)
 * @param allowList        bank-controlled allow-list YAML (MODOCUMENT_AGENT_ALLOWLIST)
 * @param secretsDir       local secrets, one file or folder per secretRef
 *                         (MODOCUMENT_SECRETS_DIR)
 * @param pollWaitSeconds  long-poll wait (≤ 30)
 * @param heartbeat        heartbeat interval (30 s)
 * @param maxBackoff       ceiling of the exponential back-off on errors
 * @param allowInsecureHttp plain http to MoDocument (local tests only)
 * @param run              false = wire everything but do not start the loop (tests)
 */
@ConfigurationProperties("modocument.agent")
public record AgentProperties(String url, String enrolmentToken, String name, Path stateDir,
                              Path allowList, Path secretsDir, Integer pollWaitSeconds,
                              Duration heartbeat, Duration maxBackoff, boolean allowInsecureHttp,
                              Boolean run) {

    public AgentProperties {
        stateDir = stateDir == null ? Path.of("/var/lib/modocument-agent") : stateDir;
        allowList = allowList == null ? Path.of("/etc/modocument-agent/allowlist.yml") : allowList;
        secretsDir = secretsDir == null ? Path.of("/etc/modocument-agent/secrets") : secretsDir;
        pollWaitSeconds = pollWaitSeconds == null ? 30 : Math.max(0, Math.min(30, pollWaitSeconds));
        heartbeat = heartbeat == null ? Duration.ofSeconds(30) : heartbeat;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
        run = run == null ? Boolean.TRUE : run;
    }

    @Override
    public String toString() {
        return "AgentProperties[url=" + url + ", name=" + name + ", stateDir=" + stateDir
                + ", allowList=" + allowList + ", secretsDir=" + secretsDir
                + ", enrolmentToken=" + (enrolmentToken == null || enrolmentToken.isBlank() ? "unset" : "set")
                + "]";
    }
}
