package com.modocument.agent;

import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.runtime.AgentLoop;
import com.modocument.agent.runtime.HealthFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Spring wiring boots without a web server (no inbound port), the
 * MoDocument URL must be https, and the token URL can only point at the
 * configured origin.
 */
@SpringBootTest(properties = {
        "modocument.agent.url=https://modocument.example",
        "modocument.agent.run=false",
        "modocument.agent.state-dir=${java.io.tmpdir}/modocument-agent-test-state",
        "modocument.agent.allow-list=${java.io.tmpdir}/modocument-agent-test-missing.yml"})
class AgentApplicationTest {

    @Autowired
    private ApplicationContext context;

    @TempDir
    Path dir;

    @Test
    void bootsWithoutAWebServer() {
        assertThat(context.getBean(AgentLoop.class)).isNotNull();
        assertThat(context.containsBean("tomcatServletWebServerFactory")).isFalse();
        assertThat(context.getClass().getSimpleName()).doesNotContain("Web");
    }

    @Test
    void theCloudUrlMustBeHttpsAndTheTokenUrlSameOrigin() {
        assertThatThrownBy(() -> new CloudClient("http://modocument.example", false, AgentHarness.MAPPER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CloudClient("https://user:pw@modocument.example", false, AgentHarness.MAPPER))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CloudClient("", false, AgentHarness.MAPPER))
                .isInstanceOf(IllegalStateException.class);
        CloudClient client = new CloudClient("https://modocument.example/", false, AgentHarness.MAPPER);
        assertThat(client.tokenUrlFor("https://modocument.example/api/integration/oauth/token"))
                .isEqualTo("https://modocument.example/api/integration/oauth/token");
        assertThat(client.tokenUrlFor("https://evil.example/steal"))
                .isEqualTo("https://modocument.example/api/integration/oauth/token");
    }

    @Test
    void theHealthFileGoesStale() {
        assertThat(HealthFile.isFresh(dir)).isFalse();
        HealthFile.touch(dir);
        assertThat(HealthFile.isFresh(dir)).isTrue();
    }
}
