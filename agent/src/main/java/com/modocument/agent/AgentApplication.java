package com.modocument.agent;

import com.modocument.agent.runtime.HealthFile;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.nio.file.Path;

/**
 * MoDocument on-prem Connector Agent (ws26, docs/integration/agent.md).
 *
 * <p>No inbound port: {@code spring.main.web-application-type=none}. The
 * agent only calls OUT to MoDocument ({@code /api/agent/v1/**}) and to the
 * bank systems its local allow-list names.
 *
 * <p>{@code java -jar modocument-agent.jar healthcheck} is the container
 * health probe: it checks the state directory's health file (touched by
 * every successful poll and heartbeat) WITHOUT starting Spring, and exits 0
 * when it is fresh, 1 otherwise.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "healthcheck".equals(args[0])) {
            String stateDir = System.getenv().getOrDefault("MODOCUMENT_AGENT_STATE_DIR",
                    "/var/lib/modocument-agent");
            System.exit(HealthFile.isFresh(Path.of(stateDir)) ? 0 : 1);
        }
        SpringApplication.run(AgentApplication.class, args);
    }
}
