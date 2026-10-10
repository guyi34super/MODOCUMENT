package com.modocument.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Bank;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.IntegrationFlow;
import com.modocument.domain.entity.Template;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.PiiKind;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import com.modocument.infrastructure.persistence.IntegrationFlowJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared H2 fixture of the ws25 integration-hub suites: a customer bank with
 * two admins and a writer, a bank-shared template (CustomerName = NAME,
 * AccountNo = ACCOUNT_NUMBER, Amount), a loopback "bank system" HTTP stub
 * and a test {@link SecretVault} (the real vault is SE1's; the port is
 * mocked as the contract says). Every row a test creates in the bank is
 * deleted afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestSupport.TestVaultConfig.class)
public abstract class IntegrationTestSupport {

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /** Reversible, bank-bound "sealing" for tests: never the real cipher. */
    @TestConfiguration
    public static class TestVaultConfig {
        @Bean
        @Primary
        public SecretVault testSecretVault() {
            return new SecretVault() {
                @Override
                public Sealed seal(int bankId, SecretMaterial material) {
                    String json = IntegrationJson.write(material.values());
                    return new Sealed("test:" + bankId + ":" + Base64.getEncoder()
                            .encodeToString(json.getBytes(StandardCharsets.UTF_8)), 1);
                }

                @Override
                @SuppressWarnings("unchecked")
                public SecretMaterial open(int bankId, Sealed sealed) {
                    String prefix = "test:" + bankId + ":";
                    if (!sealed.ciphertext().startsWith(prefix)) {
                        throw new IllegalStateException("not this bank's secret");
                    }
                    try {
                        Map<String, String> values = MAPPER.readValue(Base64.getDecoder().decode(
                                sealed.ciphertext().substring(prefix.length())), Map.class);
                        return new SecretMaterial(values);
                    } catch (Exception e) {
                        throw new IllegalStateException("tampered");
                    }
                }
            };
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected BankJpaRepository banks;
    @Autowired
    protected AppUserJpaRepository users;
    @Autowired
    protected TemplateRepository templates;
    @Autowired
    protected TemplateFieldsRepository templateFields;
    @Autowired
    protected IntegrationConnectionJpaRepository connections;
    @Autowired
    protected IntegrationFlowJpaRepository flows;
    @Autowired
    protected SecretVault vault;

    protected Bank bank;
    protected AppUser admin;
    protected AppUser secondAdmin;
    protected AppUser writer;
    protected int templateId;

    /** The "bank system": records requests, answers per path. */
    protected HttpServer stub;
    protected String stubBase;
    protected final List<String> stubRequests = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> stubBodies = Collections.synchronizedList(new ArrayList<>());
    protected final Map<String, String> stubResponses = Collections.synchronizedMap(new LinkedHashMap<>());
    protected final AtomicInteger stubStatus = new AtomicInteger(200);
    private final List<Integer> createdUsers = new ArrayList<>();

    @BeforeEach
    void seedIntegrationFixture() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        bank = banks.save(new Bank("IH" + tag, "Integration Bank " + tag));
        admin = user("ih-admin-" + tag, Role.ADMIN);
        secondAdmin = user("ih-admin2-" + tag, Role.ADMIN);
        writer = user("ih-writer-" + tag, Role.READ_WRITE);

        Template template = new Template();
        template.setName("Customers " + tag);
        template.setOwnerUserId(admin.getAppUserId());
        template.setBankId(bank.getBankId());
        template.setVisibility(TemplateVisibility.BANK);
        templates.add(template);
        templateId = template.getTemplateId();
        field("CustomerName", PiiKind.NAME);
        field("AccountNo", PiiKind.ACCOUNT_NUMBER);
        field("Amount", PiiKind.NONE);

        stub = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            stubRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            stubBodies.add(body);
            String response = stubResponses.getOrDefault(exchange.getRequestURI().getPath(), "{}");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            int status = stubStatus.get();
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterEach
    void cleanIntegrationFixture() {
        stub.stop(0);
        int id = bank.getBankId();
        for (String table : List.of("OutboundDelivery", "IntegrationRunEvent", "IntegrationRun",
                "IntegrationFlow", "IntegrationConnection", "IntegrationEvent", "PendingChange",
                "ImportDetails", "IngestionJob", "ImportHistory", "UploadedFile", "TemplateFields",
                "Template")) {
            jdbc.update("DELETE FROM " + table + " WHERE BankId = ?", id);
        }
        for (Integer user : createdUsers) {
            users.deleteById(user);
        }
        banks.deleteById(id);
    }

    private AppUser user(String prefix, Role role) {
        AppUser entity = new AppUser();
        entity.setEmail(prefix.toLowerCase() + "@example.com");
        entity.setPasswordHash("{noop}unused");
        entity.setDisplayName(prefix);
        entity.setBankId(bank.getBankId());
        entity.setRole(role);
        entity.setSettingsJson("{}");
        entity.setCreatedDate(LocalDateTime.now());
        AppUser saved = users.save(entity);
        createdUsers.add(saved.getAppUserId());
        return saved;
    }

    private void field(String name, PiiKind kind) {
        TemplateFields field = new TemplateFields();
        field.setTemplateId(templateId);
        field.setBankId(bank.getBankId());
        field.setFieldName(name);
        field.setTemplateDataTypeId(1);
        field.setPiiKind(kind);
        templateFields.add(field);
    }

    // ---- HTTP helpers -----------------------------------------------------------------------

    protected String bearer(AppUser user) {
        return "Bearer " + jwtService.issueToken(user);
    }

    protected ResultActions getAs(AppUser user, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(user)));
    }

    protected ResultActions postAs(AppUser user, String path, Object body) throws Exception {
        var request = post(path).header("Authorization", bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : MAPPER.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    protected ResultActions putAs(AppUser user, String path, Object body) throws Exception {
        return mvc.perform(put(path).header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    protected ResultActions deleteAs(AppUser user, String path) throws Exception {
        return mvc.perform(delete(path).header("Authorization", bearer(user)));
    }

    protected static JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- direct fixtures (bypassing maker/checker for engine tests) --------------------------

    protected IntegrationConnection restConnection(String name, String authType, Map<String, String> secret,
                                                   ConnectionType type) {
        IntegrationConnection connection = new IntegrationConnection();
        connection.setBankId(bank.getBankId());
        connection.setName(name);
        connection.setType(type);
        connection.setMode(ConnectionMode.DIRECT);
        Map<String, String> config = new LinkedHashMap<>();
        config.put(type == ConnectionType.WEBHOOK ? "url" : "baseUrl", stubBase + "/bank");
        connection.setConfigJson(IntegrationJson.write(config));
        connection.setAuthType(authType);
        if (secret != null) {
            SecretVault.Sealed sealed = vault.seal(bank.getBankId(), new SecretMaterial(secret));
            connection.setSecretCiphertext(sealed.ciphertext());
            connection.setSecretKeyVersion(sealed.keyVersion());
        }
        connection.setCreatedBy(admin.getAppUserId());
        connection.setCreatedAt(Instant.now());
        return connections.save(connection);
    }

    protected IntegrationFlow flow(String name, IntegrationConnection connection, FlowDirection direction,
                                   FlowTrigger trigger, Map<String, Object> spec) {
        IntegrationFlow flow = new IntegrationFlow();
        flow.setBankId(bank.getBankId());
        flow.setName(name);
        flow.setConnectionId(connection.getId());
        flow.setTemplateId(templateId);
        flow.setDirection(direction);
        flow.setTriggerType(trigger);
        if (direction == FlowDirection.INBOUND) {
            flow.setSourceSpecJson(IntegrationJson.write(spec));
        } else {
            flow.setTargetSpecJson(IntegrationJson.write(spec));
        }
        flow.setCreatedBy(admin.getAppUserId());
        flow.setCreatedAt(Instant.now());
        return flow;
    }
}
