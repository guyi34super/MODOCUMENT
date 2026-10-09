package com.modocument.infrastructure.siem;

import com.modocument.application.port.TenantContext;
import com.modocument.domain.entity.Bank;
import com.modocument.infrastructure.alerting.MinuteSweepParticipant;
import com.modocument.infrastructure.alerting.WebhookAlertChannel;
import com.modocument.infrastructure.async.TenantTaskDecorator;
import com.modocument.infrastructure.audit.AuditRecordedEvent;
import com.modocument.infrastructure.persistence.BankJpaRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional PUSH transport of the SIEM feed (ws18, SEC-031): every committed
 * audit event of the configured bank is rendered by {@link SiemEventMapper}
 * (the same schema as the pull feed) and POSTed, in batches, as
 * newline-delimited JSON ({@code application/x-ndjson}) to
 * {@code modocument.siem.webhook.url} — near-real-time, without the SIEM
 * having to poll.
 *
 * <p>SINGLE-BANK DEPLOYMENT ASSUMPTION (gate E6): the forwarder pushes the
 * events of exactly ONE bank — {@code modocument.siem.webhook.bank-code} —
 * and drops every other bank's events before they are even rendered. A
 * deployment serving several banks leaves this off and gives each bank's
 * SIEM the PULL feed (GET /api/siem/events), which is scoped by the
 * caller's own token. A url without a bank-code refuses to start.
 *
 * <p>Delivery: post-commit listener -> bounded in-memory queue -> one
 * single-flight flush on a private executor (never the business thread).
 * A failed POST puts its batch back at the head of the queue (order
 * preserved) and the alert framework's MINUTE SWEEP retries it
 * ({@link MinuteSweepParticipant}). At-least-once while the process lives;
 * queue overflow drops the OLDEST events and a restart loses the queue —
 * both are logged, and the pull feed (cursor = last event id the SIEM saw)
 * is the gap-free backfill.
 *
 * <p>Like the alert webhook, the url is OPERATOR configuration and does not
 * go through the user-input SSRF policy (OutboundHostPolicy) — see
 * WebhookAlertChannel for the reasoning. Bodies are signed
 * ({@code X-MoDocument-Signature: sha256=<hmac>}) when a secret is set.
 *
 * <p>Tenancy (call-site registry in {@link TenantContext}): the bank-code
 * lookup reads the RLS-protected Bank table {@code runAsSystem}; nothing
 * else here touches the database.
 */
@Component
public class SiemWebhookForwarder implements MinuteSweepParticipant {

    private static final Logger log = LoggerFactory.getLogger(SiemWebhookForwarder.class);

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final SiemProperties.Webhook config;
    private final SiemEventMapper mapper;
    private final BankJpaRepository banks;
    private final RestClient client;
    private final LinkedBlockingDeque<String> queue;
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolTaskExecutor executor;
    private volatile Integer bankId;

    public SiemWebhookForwarder(SiemProperties properties, SiemEventMapper mapper,
                                BankJpaRepository banks) {
        this.config = properties.getWebhook();
        this.mapper = mapper;
        this.banks = banks;
        this.queue = new LinkedBlockingDeque<>(Math.max(1, config.getQueueCapacity()));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout());
        factory.setReadTimeout(config.getReadTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("siem-push-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setTaskDecorator(new TenantTaskDecorator());
        executor.initialize();
        if (enabled() && (config.getBankCode() == null || config.getBankCode().isBlank())) {
            throw new IllegalStateException("modocument.siem.webhook.url is set but "
                    + "modocument.siem.webhook.bank-code is not: the push forwarder serves "
                    + "exactly one bank (single-bank deployment). Multi-bank deployments use "
                    + "the pull feed GET /api/siem/events. See docs/security/siem-event-schema.md.");
        }
        if (enabled()) {
            java.net.URI uri = java.net.URI.create(config.getUrl().trim());
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    && !(host.equals("localhost") || host.startsWith("127."))) {
                log.warn("SIEM push target {}://{} is not HTTPS: audit events will travel in "
                        + "clear text.", uri.getScheme(), host);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public boolean enabled() {
        return config.getUrl() != null && !config.getUrl().isBlank();
    }

    /** The bound bank, once the configured code resolved. */
    public Optional<Integer> boundBankId() {
        return Optional.ofNullable(bankId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resolveOnStartup() {
        if (enabled()) {
            resolveBank();
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditRecorded(AuditRecordedEvent recorded) {
        Integer bound = bankId;
        if (!enabled() || bound == null || recorded == null || recorded.event() == null
                || recorded.event().getBankId() != bound) {
            return; // another bank's event never leaves the process (E6)
        }
        try {
            enqueue(mapper.toLine(recorded.event()));
            triggerFlush();
        } catch (RuntimeException e) {
            log.error("SIEM push could not queue audit event {}", recorded.event().getAuditEventId(), e);
        }
    }

    @Override
    public String name() {
        return "siem-webhook";
    }

    /** Minute sweep: bind a late bank, retry what is queued. */
    @Override
    public void onSweep(Instant now) {
        if (!enabled()) {
            return;
        }
        if (bankId == null) {
            resolveBank();
        }
        if (!queue.isEmpty()) {
            triggerFlush();
        }
    }

    /** Events waiting to be pushed (tests, diagnostics). */
    public int pending() {
        return queue.size();
    }

    // ---- internals ---------------------------------------------------------

    private void resolveBank() {
        try {
            Optional<Bank> bank = TenantContext.runAsSystem(
                    () -> banks.findByCode(config.getBankCode().trim()));
            if (bank.isPresent()) {
                bankId = bank.get().getBankId();
                log.info("SIEM push forwarder bound to bank {} ({})", bankId, config.getBankCode());
            } else {
                log.error("SIEM push forwarder: bank-code '{}' names no bank - nothing is pushed "
                        + "until it exists (re-checked every sweep).", config.getBankCode());
            }
        } catch (Exception e) {
            log.error("SIEM push forwarder could not resolve its bank", e);
        }
    }

    private void enqueue(String line) {
        while (!queue.offerLast(line)) {
            if (queue.pollFirst() != null) {
                long total = dropped.incrementAndGet();
                if (total == 1 || total % 1000 == 0) {
                    log.warn("SIEM push queue full: {} oldest event(s) dropped so far; the "
                            + "SIEM must backfill from the pull feed.", total);
                }
            }
        }
    }

    private void triggerFlush() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::flush);
        } catch (TaskRejectedException e) {
            flushing.set(false);
        }
    }

    private void flush() {
        boolean failed = false;
        try {
            while (!queue.isEmpty()) {
                List<String> batch = new ArrayList<>(config.getBatchSize());
                queue.drainTo(batch, Math.max(1, config.getBatchSize()));
                if (batch.isEmpty()) {
                    break;
                }
                try {
                    post(batch);
                } catch (Exception e) {
                    // Back at the head, in order; the minute sweep retries.
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        if (!queue.offerFirst(batch.get(i))) {
                            dropped.incrementAndGet();
                        }
                    }
                    log.warn("SIEM push of {} event(s) failed ({}); {} queued for the next sweep",
                            batch.size(), e.getClass().getSimpleName(), queue.size());
                    failed = true;
                    break;
                }
            }
        } finally {
            flushing.set(false);
        }
        // An event queued between the last drain and the reset above would
        // otherwise wait for the sweep.
        if (!failed && !queue.isEmpty()) {
            triggerFlush();
        }
    }

    private void post(List<String> batch) {
        String body = String.join("\n", batch) + "\n";
        RestClient.RequestBodySpec request = client.post()
                .uri(config.getUrl().trim())
                .contentType(NDJSON);
        if (config.getSecret() != null && !config.getSecret().isBlank()) {
            request = request.header(WebhookAlertChannel.SIGNATURE_HEADER,
                    "sha256=" + WebhookAlertChannel.hmac(config.getSecret(), body));
        }
        request.body(body).retrieve().toBodilessEntity();
    }
}
