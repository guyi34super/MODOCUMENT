package com.modocument.infrastructure.integration;

import com.modocument.application.port.integration.ClientPrincipal;
import com.modocument.application.usecase.IntegrationApiException;
import com.modocument.domain.entity.IdempotencyKey;
import com.modocument.infrastructure.persistence.IdempotencyKeyJpaRepository;
import com.modocument.infrastructure.security.client.IntegrationClientProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * {@code Idempotency-Key} for the integration API's POSTs (ws26), per
 * (bank, client, key), remembered for {@code idempotency-ttl} (24 h):
 * <ul>
 *   <li>first use → a reservation row is committed at once (its own
 *       transaction, so a concurrent duplicate sees it), the request runs,
 *       and its 2xx answer is stored; a failed request releases the key;</li>
 *   <li>same key + same request hash → the stored answer is replayed
 *       (same status, same body — e.g. the same {@code jobId});</li>
 *   <li>same key + different request → 409 IDEMPOTENCY_KEY_REUSED;</li>
 *   <li>same key while the first request is still running → 409
 *       IDEMPOTENCY_KEY_IN_PROGRESS.</li>
 * </ul>
 * Runs under the client's tenant (the RLS-protected table is written as the
 * client's bank); expired keys of the bank are purged on each use.
 */
@Component
public class IdempotencyStore {

    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyKeyJpaRepository keys;
    private final IntegrationClientProperties properties;
    private final TransactionTemplate newTx;

    public IdempotencyStore(IdempotencyKeyJpaRepository keys,
                            IntegrationClientProperties properties,
                            PlatformTransactionManager transactionManager) {
        this.keys = keys;
        this.properties = properties;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newTx = template;
    }

    /** Either run the request under {@code reservationId}, or replay a stored answer. */
    public record Outcome(Long reservationId, Integer replayStatus, String replayBody) {

        public boolean isReplay() {
            return reservationId == null;
        }
    }

    /** SHA-256 over the parts that make two requests "the same". */
    public static String requestHash(String method, String route, byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(route.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (byte[] part : parts) {
                digest.update((byte) '\n');
                digest.update(part == null ? new byte[0] : part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Outcome begin(ClientPrincipal client, String key, String requestHash) {
        String idemKey = validKey(key);
        try {
            return newTx.execute(status -> {
                Instant now = Instant.now();
                keys.deleteExpired(client.bankId(), now);
                Optional<IdempotencyKey> existing = keys.findByBankIdAndApiClientIdAndIdemKey(
                        client.bankId(), client.apiClientId(), idemKey);
                if (existing.isPresent()) {
                    IdempotencyKey row = existing.get();
                    if (!row.getRequestHash().equals(requestHash)) {
                        throw IntegrationApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used for a different request.");
                    }
                    if (row.getResponseStatus() == null) {
                        throw IntegrationApiException.conflict("IDEMPOTENCY_KEY_IN_PROGRESS",
                                "A request with this Idempotency-Key is still being processed.");
                    }
                    return new Outcome(null, row.getResponseStatus(), row.getResponseBody());
                }
                IdempotencyKey row = new IdempotencyKey();
                row.setBankId(client.bankId());
                row.setApiClientId(client.apiClientId());
                row.setIdemKey(idemKey);
                row.setRequestHash(requestHash);
                row.setCreatedAt(now);
                row.setExpiresAt(now.plus(properties.getApi().getIdempotencyTtl()));
                IdempotencyKey saved = keys.saveAndFlush(row);
                return new Outcome(saved.getIdempotencyKeyId(), null, null);
            });
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            throw IntegrationApiException.conflict("IDEMPOTENCY_KEY_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed.");
        }
    }

    /** Stores the answer to replay (bodies above the configured size are not kept). */
    public void complete(long reservationId, int status, String body) {
        String stored = body != null
                && body.length() <= properties.getApi().getIdempotencyMaxResponseChars()
                ? body : null;
        newTx.executeWithoutResult(tx -> keys.findById(reservationId).ifPresent(row -> {
            row.setResponseStatus(status);
            row.setResponseBody(stored);
            keys.save(row);
        }));
    }

    /** The request failed: the key is free again (a corrected retry may reuse it). */
    public void release(long reservationId) {
        newTx.executeWithoutResult(tx -> keys.findById(reservationId).ifPresent(keys::delete));
    }

    private String validKey(String key) {
        if (key == null || key.isBlank()) {
            throw IntegrationApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header (e.g. a UUID) with every POST.");
        }
        String trimmed = key.trim();
        if (trimmed.length() > properties.getApi().getIdempotencyKeyMaxLength()
                || !trimmed.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
            throw IntegrationApiException.badRequest("IDEMPOTENCY_KEY_INVALID",
                    "The Idempotency-Key must be 1-" + properties.getApi()
                            .getIdempotencyKeyMaxLength() + " printable ASCII characters.");
        }
        return trimmed;
    }
}
