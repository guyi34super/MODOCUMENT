package com.modocument.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.modocument.agent.allowlist.AllowList;
import com.modocument.agent.cloud.AgentTls;
import com.modocument.agent.cloud.CloudClient;
import com.modocument.agent.exec.DatabaseConnector;
import com.modocument.agent.exec.RestConnector;
import com.modocument.agent.exec.SftpConnector;
import com.modocument.agent.exec.TaskExecutor;
import com.modocument.agent.runtime.AgentLoop;
import com.modocument.agent.secrets.LocalSecrets;
import com.modocument.agent.state.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Wires the agent from {@link AgentProperties}; the runner starts the loop. */
@Configuration
public class AgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentConfiguration.class);

    @Bean
    public ObjectMapper agentObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    public AgentLoop agentLoop(AgentProperties properties, ObjectMapper mapper,
                               ObjectProvider<BuildProperties> build) {
        String version = build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
        LocalSecrets secrets = new LocalSecrets(properties.secretsDir(), System::getenv, mapper);
        AgentTls.ClientCertificate certificate = clientCertificate(properties, secrets);
        AgentTls.ClientAuth auth = certificate == null ? AgentTls.ClientAuth.oauth2()
                : new AgentTls.ClientAuth(AgentTls.ClientAuth.MTLS, certificate.certificatePem());
        SSLContext tls = AgentTls.sslContext(certificate, trustedCa(properties.trustedCaFile()));
        CloudClient cloud = new CloudClient(properties.url(), properties.allowInsecureHttp(), mapper, tls);
        AllowList allowList = new AllowList(properties.allowList());
        if (allowList.isEmpty()) {
            log.warn("The allow-list {} allows nothing: every task will be refused until the bank "
                    + "lists its destinations, tables and folders.", properties.allowList());
        }
        TaskExecutor executor = new TaskExecutor(List.of(new DatabaseConnector(mapper),
                new RestConnector(mapper), new SftpConnector(mapper)), allowList, secrets, cloud);
        log.info("MoDocument Connector Agent {} starting: {}", version, properties);
        return new AgentLoop(cloud, new CredentialStore(properties.stateDir(), mapper), executor,
                properties.stateDir(), version, agentName(properties), properties.enrolmentToken(),
                properties.pollWaitSeconds(), properties.heartbeat(), properties.maxBackoff(), auth);
    }

    /** MTLS: the client certificate + key from the local secret store; null for OAuth2. */
    public static AgentTls.ClientCertificate clientCertificate(AgentProperties properties, LocalSecrets secrets) {
        String method = AgentTls.ClientAuth.normalize(properties.authMethod());
        if (!AgentTls.ClientAuth.MTLS.equals(method)) {
            return null;
        }
        return AgentTls.ClientCertificate.from(secrets.resolve(properties.clientCertRef()),
                properties.clientCertRef());
    }

    private static String trustedCa(Path file) {
        if (file == null || file.toString().isBlank()) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("MODOCUMENT_AGENT_TRUSTED_CA_FILE " + file
                    + " cannot be read.", e);
        }
    }

    @Bean
    public ApplicationRunner agentRunner(AgentProperties properties, AgentLoop loop) {
        return args -> {
            if (!properties.run()) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(loop::stop, "agent-shutdown"));
            loop.run();
        };
    }

    private static String agentName(AgentProperties properties) {
        if (properties.name() != null && !properties.name().isBlank()) {
            return properties.name().strip();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "modocument-agent";
        }
    }
}
