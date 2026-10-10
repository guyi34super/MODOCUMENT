package com.modocument.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.integration.SecretMaterial;
import com.modocument.application.port.integration.SecretVault;
import com.modocument.domain.entity.IntegrationConnection;
import com.modocument.domain.entity.PendingChange;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.ChangeType;
import com.modocument.domain.model.integration.ConnectionMode;
import com.modocument.domain.model.integration.ConnectionType;
import com.modocument.infrastructure.integration.engine.ConnectionValidator;
import com.modocument.infrastructure.integration.engine.IntegrationConnectors;
import com.modocument.infrastructure.integration.engine.IntegrationJson;
import com.modocument.infrastructure.persistence.IntegrationConnectionJpaRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * INTEGRATION_CONNECTION_CHANGE under dual control (ws25): a new connection,
 * or a change of an existing one's host, settings or credentials.
 *
 * <p><b>Submit</b> ({@link #prepare}, as the maker): validates name, type,
 * mode, agent, config and auth type; a plaintext {@code secret} is SEALED
 * through the {@link SecretVault} right here, so the stored payload holds
 * only {@code secretCiphertext} + {@code secretKeyVersion} — never the
 * secret. A raw {@code secretCiphertext} in the maker's input is refused.
 *
 * <p><b>Apply</b> (a second admin): re-validates, refuses a connection that
 * changed since the request ({@code baseVersion}), stores it, resets its
 * status to UNTESTED and audits {@code INTEGRATION_CONNECTION_CHANGED}.
 *
 * <p>Payload: {@code {connectionId?, name, type, mode, agentId?, config,
 * authType, secretCiphertext?, secretKeyVersion?, secretRef?, baseVersion?}}.
 */
@Component
public class IntegrationConnectionChangeApplier implements PendingChangeApplier {

    private final IntegrationConnectionJpaRepository connections;
    private final ConnectionValidator validator;
    private final SecretVault vault;
    private final CurrentUserProvider currentUser;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public IntegrationConnectionChangeApplier(IntegrationConnectionJpaRepository connections,
                                              ConnectionValidator validator, SecretVault vault,
                                              CurrentUserProvider currentUser, AuditRecorder audit,
                                              ObjectMapper objectMapper) {
        this.connections = connections;
        this.validator = validator;
        this.vault = vault;
        this.currentUser = currentUser;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChangeType type() {
        return ChangeType.INTEGRATION_CONNECTION_CHANGE;
    }

    @Override
    public Prepared prepare(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("The change payload must be an object.");
        }
        if (payload.has("secretCiphertext") || payload.has("secretKeyVersion")) {
            throw new IllegalArgumentException("Send the secret in 'secret'; it is sealed on submit.");
        }
        int bankId = currentUser.requireBankId();
        Long connectionId = payload.hasNonNull("connectionId") ? payload.get("connectionId").asLong() : null;
        IntegrationConnection existing = connectionId == null ? null
                : connections.findByIdAndBankId(connectionId, bankId).orElseThrow(
                        () -> new NoSuchElementException("Connection " + connectionId + " not found."));
        Parsed parsed = parse(payload, existing);
        requireUniqueName(bankId, parsed.name(), existing);

        SecretMaterial secret = secret(payload);
        if (secret != null && parsed.mode() == ConnectionMode.AGENT) {
            throw new IllegalArgumentException("An AGENT connection keeps its credentials on the "
                    + "agent: send 'secretRef', not 'secret'.");
        }
        if (existing == null && parsed.mode() == ConnectionMode.DIRECT && secret == null
                && !"NONE".equals(parsed.authType())) {
            throw new IllegalArgumentException("A new connection needs its 'secret'.");
        }
        validator.validate(IntegrationConnectors.context(bankId, connectionId, parsed.name(),
                parsed.type(), parsed.mode(), parsed.config(), parsed.authType(),
                secret == null ? SecretMaterial.EMPTY : secret), secret != null);

        ObjectNode stored = objectMapper.createObjectNode();
        if (connectionId != null) {
            stored.put("connectionId", connectionId);
            stored.put("baseVersion", existing.getVersion());
        }
        stored.put("name", parsed.name());
        stored.put("type", parsed.type().name());
        stored.put("mode", parsed.mode().name());
        if (parsed.agentId() != null) {
            stored.put("agentId", parsed.agentId());
        }
        stored.set("config", objectMapper.valueToTree(parsed.config()));
        stored.put("authType", parsed.authType());
        if (parsed.secretRef() != null) {
            stored.put("secretRef", parsed.secretRef());
        }
        if (secret != null) {
            SecretVault.Sealed sealed = vault.seal(bankId, secret);
            stored.put("secretCiphertext", sealed.ciphertext());
            stored.put("secretKeyVersion", sealed.keyVersion());
        }
        return new Prepared(stored, connectionId == null ? null : String.valueOf(connectionId));
    }

    @Override
    public void apply(PendingChange change) {
        JsonNode payload = ChangePayloads.parse(objectMapper, change.getPayloadJson());
        int bankId = change.getBankId();
        Long connectionId = payload.hasNonNull("connectionId") ? payload.get("connectionId").asLong() : null;
        IntegrationConnection connection;
        boolean created = connectionId == null;
        if (created) {
            connection = new IntegrationConnection();
            connection.setBankId(bankId);
            connection.setCreatedBy(change.getMakerUserId());
            connection.setCreatedAt(Instant.now());
        } else {
            connection = connections.findByIdAndBankId(connectionId, bankId).orElseThrow(
                    () -> new NoSuchElementException("Connection " + connectionId + " not found."));
            int baseVersion = payload.path("baseVersion").asInt(-1);
            if (baseVersion != connection.getVersion()) {
                throw new IllegalStateException("The connection changed after this request was "
                        + "made; submit the change again.");
            }
        }
        Parsed parsed = parse(payload, connection.getId() == null ? null : connection);
        requireUniqueName(bankId, parsed.name(), created ? null : connection);
        connection.setName(parsed.name());
        connection.setType(parsed.type());
        connection.setMode(parsed.mode());
        connection.setAgentId(parsed.mode() == ConnectionMode.AGENT ? parsed.agentId() : null);
        connection.setConfigJson(IntegrationJson.write(parsed.config()));
        connection.setAuthType(parsed.authType());
        if (parsed.mode() == ConnectionMode.AGENT) {
            connection.setSecretCiphertext(null);
            connection.setSecretKeyVersion(null);
            connection.setSecretRef(parsed.secretRef());
        } else {
            connection.setSecretRef(null);
            if (payload.hasNonNull("secretCiphertext")) {
                connection.setSecretCiphertext(payload.get("secretCiphertext").asText());
                connection.setSecretKeyVersion(payload.path("secretKeyVersion").asInt(1));
            }
        }
        if (!IntegrationConnection.STATUS_DISABLED.equals(connection.getStatus())) {
            connection.setStatus(IntegrationConnection.STATUS_UNTESTED);
        }
        connection.setUpdatedAt(Instant.now());
        IntegrationConnection saved = connections.save(connection);
        audit.record(AuditEventSpec.builder("INTEGRATION_CONNECTION_CHANGED", AuditOutcome.SUCCESS)
                .entity("IntegrationConnection", String.valueOf(saved.getId()))
                .newValues(auditValues(saved, created ? "CREATED" : "UPDATED",
                        payload.hasNonNull("secretCiphertext")))
                .build());
    }

    /** The audited view of a connection change: never config values that could be secrets. */
    public static Map<String, Object> auditValues(IntegrationConnection connection, String change,
                                                  boolean secretChanged) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("connectionId", connection.getId());
        values.put("name", connection.getName());
        values.put("type", connection.getType() == null ? null : connection.getType().name());
        values.put("mode", connection.getMode() == null ? null : connection.getMode().name());
        values.put("authType", connection.getAuthType());
        values.put("change", change);
        values.put("secretChanged", secretChanged);
        return values;
    }

    // ---- parsing -------------------------------------------------------------------

    record Parsed(String name, ConnectionType type, ConnectionMode mode, Integer agentId,
                  Map<String, String> config, String authType, String secretRef) {
    }

    static Parsed parse(JsonNode payload, IntegrationConnection existing) {
        String name = ChangePayloads.requireText(payload, "name", 100).trim();
        ConnectionType type = enumOf(ConnectionType.class,
                ChangePayloads.requireText(payload, "type", 20), "type");
        if (existing != null && existing.getType() != type) {
            throw new IllegalArgumentException("A connection's type cannot change; create a new one.");
        }
        String modeText = ChangePayloads.optionalText(payload, "mode", 10);
        ConnectionMode mode = modeText == null ? ConnectionMode.DIRECT
                : enumOf(ConnectionMode.class, modeText, "mode");
        Integer agentId = payload.hasNonNull("agentId") ? ChangePayloads.requireInt(payload, "agentId") : null;
        if (mode == ConnectionMode.AGENT && agentId == null) {
            throw new IllegalArgumentException("An AGENT connection needs its 'agentId'.");
        }
        if (mode == ConnectionMode.DIRECT && agentId != null) {
            throw new IllegalArgumentException("Only an AGENT connection names an agent.");
        }
        Map<String, String> config = new LinkedHashMap<>();
        JsonNode configNode = payload.get("config");
        if (configNode != null && !configNode.isNull()) {
            if (!configNode.isObject()) {
                throw new IllegalArgumentException("'config' must be an object.");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = configNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().length() > 64 || !field.getKey().matches("[A-Za-z][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Config keys are plain names.");
                }
                JsonNode value = field.getValue();
                if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                    continue;
                }
                if (!value.isValueNode()) {
                    throw new IllegalArgumentException("Config values are plain text or numbers.");
                }
                config.put(field.getKey(), value.asText().trim());
            }
            if (config.size() > 40) {
                throw new IllegalArgumentException("Too many config settings.");
            }
        }
        String authType = ConnectionValidator.authTypeFor(type,
                ChangePayloads.optionalText(payload, "authType", 30));
        String secretRef = ChangePayloads.optionalText(payload, "secretRef", 200);
        if (secretRef != null && secretRef.isBlank()) {
            secretRef = null;
        }
        if (mode == ConnectionMode.AGENT && secretRef == null && !"NONE".equals(authType)) {
            throw new IllegalArgumentException("An AGENT connection needs 'secretRef', the name of "
                    + "the secret in the agent's local store.");
        }
        if (mode == ConnectionMode.DIRECT && secretRef != null) {
            throw new IllegalArgumentException("'secretRef' is for AGENT connections only.");
        }
        if (secretRef != null && !secretRef.matches("[A-Za-z0-9_./:-]{1,200}")) {
            throw new IllegalArgumentException("'secretRef' must be a plain secret name.");
        }
        return new Parsed(name, type, mode, agentId, config, authType, secretRef);
    }

    private SecretMaterial secret(JsonNode payload) {
        JsonNode node = payload.get("secret");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("'secret' must be an object.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!ConnectionValidator.SECRET_KEYS.contains(field.getKey())) {
                throw new IllegalArgumentException("Unknown secret key '" + field.getKey()
                        .replaceAll("[^A-Za-z0-9_]", "?") + "'.");
            }
            if (field.getValue() == null || field.getValue().isNull()) {
                continue;
            }
            if (!field.getValue().isTextual() || field.getValue().asText().length() > 20_000) {
                throw new IllegalArgumentException("Secret values are text (at most 20,000 characters).");
            }
            values.put(field.getKey(), field.getValue().asText());
        }
        return values.isEmpty() ? null : new SecretMaterial(values);
    }

    private void requireUniqueName(int bankId, String name, IntegrationConnection existing) {
        boolean taken = connections.findByBankIdOrderByNameAsc(bankId).stream()
                .anyMatch(other -> other.getName().equalsIgnoreCase(name)
                        && (existing == null || !other.getId().equals(existing.getId())));
        if (taken) {
            throw new IllegalStateException("A connection named '" + name + "' already exists.");
        }
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + field + "' must be one of "
                    + java.util.Arrays.toString(type.getEnumConstants()) + ".");
        }
    }
}
