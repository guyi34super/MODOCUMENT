package com.modocument.infrastructure.ai;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.AuditEventSpec;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.port.SettingsReader;
import com.modocument.application.port.TenantContext;
import com.modocument.application.privacy.PiiPatterns;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.domain.model.settings.SettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * THE AI gateway (SUP-S13, gates C8/C9/C10): the only component in the
 * codebase allowed to call the Spring AI chat client (a guard test enforces
 * this). Every prompt arrives as a {@link PromptEnvelope} whose parts were
 * tokenized by the call site, and the gateway enforces, in order:
 *
 * <ol>
 *   <li><b>One bank per prompt (C9)</b> — a non-empty {@link TenantContext}
 *       must be bound, it must equal the envelope's bank, and every part
 *       that names a source bank must name that same bank. Anything else is
 *       refused with an {@code AI_PROMPT_REFUSED} audit event: code can not
 *       build a prompt that mixes two banks' data.</li>
 *   <li><b>No raw identifiers out (C9, defense in depth)</b> — the assembled
 *       prompt is re-scanned with the shared {@link PiiPatterns}; a raw
 *       NIC/account/email shape means a call site skipped tokenization, so
 *       the call is refused ({@code AI_PROMPT_BLOCKED_PII}) instead of
 *       trusted.</li>
 *   <li><b>No content in logs (C8)</b> — the gateway logs purposes, models
 *       and lengths only, never prompt or completion text, and the
 *       {@code AI_CALL} audit evidence carries counts, never content.</li>
 *   <li><b>Output scanning (C10)</b> — every completion goes through
 *       {@link AiOutputScanner} before it is returned: STRICT purposes
 *       reject a suspicious completion ({@code AI_OUTPUT_BLOCKED}, the
 *       caller falls back to its non-AI path), LENIENT purposes scrub and
 *       flag it ({@code AI_OUTPUT_FLAGGED}).</li>
 * </ol>
 *
 * <p>Availability (ws23): a bank whose {@code ai.enabled} setting is off gets
 * no model call at all, and every call passes the {@link AiCircuitBreaker}
 * — while it is open the call is refused at once instead of waiting on a
 * failing endpoint. Both refusals are {@link AiUnavailableException} (503 at
 * the API, the code fallback in the ingestion parse stage).
 */
@Service
public class AiGateway implements AiFilesService {

    /**
     * Sentinel bridged into spring.ai.openai.api-key when no real key is
     * configured, so the OpenAI auto-configuration can boot keyless.
     */
    public static final String UNSET_API_KEY = "unset";

    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    /** Raw identifier shapes that must never leave the backend in a prompt. */
    private static final Map<String, Pattern> PROMPT_LEAK_SHAPES = Map.of(
            "NIC", PiiPatterns.NIC,
            "ACCOUNT", PiiPatterns.ACCOUNT,
            "EMAIL", PiiPatterns.EMAIL);

    private final AiFilesProperties properties;
    private final ChatClient chatClient;
    private final AiOutputScanner outputScanner;
    private final AuditRecorder audit;
    private final AiCircuitBreaker circuitBreaker;
    private final SettingsReader settings;

    /** Without a circuit breaker or settings (every bank AI-enabled) — unit tests. */
    public AiGateway(AiFilesProperties properties, ChatClient chatClient,
                     AiOutputScanner outputScanner, AuditRecorder audit) {
        this(properties, chatClient, outputScanner, audit, AiCircuitBreaker.disabled(), null);
    }

    @Autowired
    public AiGateway(AiFilesProperties properties, ChatClient chatClient,
                     AiOutputScanner outputScanner, AuditRecorder audit,
                     AiCircuitBreaker circuitBreaker, SettingsReader settings) {
        this.properties = properties;
        this.chatClient = chatClient;
        this.outputScanner = outputScanner;
        this.audit = audit;
        this.circuitBreaker = circuitBreaker == null ? AiCircuitBreaker.disabled() : circuitBreaker;
        this.settings = settings;
    }

    @Override
    public boolean isConfigured() {
        String apiKey = properties.getApiKey();
        return apiKey != null && !apiKey.isBlank() && !UNSET_API_KEY.equals(apiKey);
    }

    @Override
    public String sendPrompt(PromptEnvelope envelope) {
        String prompt = accept(envelope);
        return callModel(envelope, prompt);
    }

    @Override
    public <T> T sendForObject(PromptEnvelope envelope, ParameterizedTypeReference<T> type) {
        String prompt = accept(envelope);
        // Keep Spring AI's JSON-schema/format instructions in the prompt so the
        // model still returns matching JSON, but parse the reply with our
        // lenient/repairing parser instead of the strict converter — LLMs emit
        // off-spec JSON (e.g. `.5`) that would otherwise throw a 500.
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String raw = callModel(envelope, prompt + "\n" + converter.getFormat());
        if (LlmStructuredJsonParser.looksLikeInstructionEcho(raw)) {
            throw new AiPromptRefusedException(
                    "The AI model repeated its own formatting instructions instead of returning a result. "
                            + "This usually means the selected model is too small or the input exceeded its "
                            + "context window. Nothing was changed — try a smaller selection or a stronger model.");
        }
        try {
            return LlmStructuredJsonParser.parseInto(raw, type.getType());
        } catch (RuntimeException lenientFailed) {
            // Last resort: never worse than the previous strict behavior.
            return converter.convert(raw == null ? "" : raw);
        }
    }

    /**
     * OCR of one page image (gate C8: off by default, explicit opt-in). An
     * image cannot be tokenized, so this path exists only behind the
     * vision-OCR policy switch; it still runs the full tenancy check, logs
     * no content and audits the call. The completion is the document's own
     * text, so the identifier scan is off ({@link AiPurpose#VISION_OCR}).
     */
    public String ocrImage(PromptEnvelope envelope, byte[] pngBytes) {
        String instruction = accept(envelope);
        ChatClientRequestSpec request = chatClient.prompt();
        String model = envelope.modelId();
        if (model != null && !model.isBlank()) {
            request = request.options(OpenAiChatOptions.builder().model(model).build());
        }
        ChatClientRequestSpec ready = request
                .user(u -> u.text(instruction)
                        .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(pngBytes))));
        ChatResponse response = guarded(() -> ready.call().chatResponse());
        return finish(envelope, instruction, response);
    }

    /** Tenancy + part-provenance + raw-identifier gatekeeping; returns the prompt text. */
    private String accept(PromptEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Prompt envelope must be provided.");
        }
        String prompt = envelope.text();
        requireConfigured(prompt);

        Optional<Integer> boundBank = TenantContext.currentBankId();
        if (boundBank.isEmpty() || envelope.bankId() == null
                || !boundBank.get().equals(envelope.bankId())) {
            refuse("AI_PROMPT_REFUSED", envelope, Map.of(
                    "reason", boundBank.isEmpty() ? "no tenant context bound"
                            : "envelope bank does not match the bound tenant",
                    "boundBank", boundBank.map(String::valueOf).orElse("none"),
                    "envelopeBank", String.valueOf(envelope.bankId())));
            throw new AiPromptRefusedException("AI prompt refused: the call is not bound to exactly "
                    + "the one bank the prompt's data belongs to.");
        }
        for (PromptEnvelope.Part part : envelope.parts()) {
            if (part.bankId() != null && !part.bankId().equals(envelope.bankId())) {
                refuse("AI_PROMPT_REFUSED", envelope, Map.of(
                        "reason", "prompt part sourced from another bank",
                        "envelopeBank", String.valueOf(envelope.bankId()),
                        "partBank", String.valueOf(part.bankId())));
                throw new AiPromptRefusedException(
                        "AI prompt refused: it would mix data from two banks.");
            }
        }

        Set<String> leaked = new LinkedHashSet<>();
        for (Map.Entry<String, Pattern> shape : PROMPT_LEAK_SHAPES.entrySet()) {
            if (shape.getValue().matcher(prompt).find()) {
                leaked.add(shape.getKey());
            }
        }
        if (!leaked.isEmpty()) {
            // A raw shape here means a call site skipped tokenization — never
            // send it, and never quote the value in the log/audit either.
            refuse("AI_PROMPT_BLOCKED_PII", envelope, Map.of("shapes", leaked.toString()));
            throw new AiPromptRefusedException("AI prompt refused: it still contains raw "
                    + "identifier-shaped values (" + leaked
                    + "); the call site must tokenize them first.");
        }
        if (!aiEnabledFor(envelope.bankId())) {
            throw AiUnavailableException.disabledForBank();
        }
        return prompt;
    }

    /** The bank's {@code ai.enabled} master switch (on without a settings reader). */
    private boolean aiEnabledFor(Integer bankId) {
        if (settings == null || bankId == null) {
            return true;
        }
        try {
            return settings.getBool(SettingKeys.AI_ENABLED, bankId);
        } catch (RuntimeException e) {
            // An unreadable setting must not silently switch the AI off.
            log.warn("Could not read ai.enabled for bank {}: {}", bankId, e.getClass().getSimpleName());
            return true;
        }
    }

    /**
     * Runs one model call through the circuit breaker: refused at once while
     * it is open; every outcome is reported back so transport failures,
     * timeouts and 5xx open it and an answer closes it.
     */
    private ChatResponse guarded(Supplier<ChatResponse> call) {
        if (!circuitBreaker.tryAcquire()) {
            throw AiUnavailableException.circuitOpen(circuitBreaker.secondsUntilRetry());
        }
        ChatResponse response;
        try {
            response = call.get();
        } catch (RuntimeException | Error e) {
            // Only transport failures, timeouts and 5xx count; anything else
            // means the endpoint answered and just releases the trial slot.
            circuitBreaker.onFailure(e);
            throw e;
        }
        circuitBreaker.onSuccess();
        return response;
    }

    private String callModel(PromptEnvelope envelope, String prompt) {
        ChatClientRequestSpec request = chatClient.prompt();
        String model = envelope.modelId();
        if (model != null && !model.isBlank()) {
            request = request.options(OpenAiChatOptions.builder().model(model).build());
        }
        ChatClientRequestSpec ready = request.user(prompt);
        ChatResponse response = guarded(() -> ready.call().chatResponse());
        return finish(envelope, prompt, response);
    }

    /** Output scan + AI_CALL evidence; returns the (possibly scrubbed) completion. */
    private String finish(PromptEnvelope envelope, String prompt, ChatResponse response) {
        String completion = response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                ? "" : response.getResult().getOutput().getText();
        AiPurpose purpose = envelope.purpose();
        // C8 evidence: lengths and purpose only — prompt/completion content
        // never reaches a log line at any level.
        log.debug("AI call: purpose={} model={} promptChars={} completionChars={}",
                purpose, effectiveModel(envelope), prompt.length(), completion.length());

        AiOutputScanner.ScanResult scan =
                outputScanner.scan(completion, prompt, purpose.scanIdentifiers());
        if (!scan.clean()) {
            Map<String, Object> finding = Map.of(
                    "purpose", purpose.name(),
                    "identifierShapes", scan.identifierCategories().toString(),
                    "instructionEcho", scan.instructionEcho());
            if (purpose.outputPolicy() == AiPurpose.OutputPolicy.STRICT) {
                audit.record(AuditEventSpec.builder("AI_OUTPUT_BLOCKED", AuditOutcome.DENIED)
                        .entity("AiCompletion", purpose.name())
                        .newValues(finding)
                        .build());
                throw new AiOutputRejectedException("The AI reply was withheld: it contained "
                        + (scan.identifierCategories().isEmpty()
                                ? "instruction-injection language"
                                : "an identifier-shaped value that was never part of the request")
                        + ". Nothing was changed.");
            }
            audit.record(AuditEventSpec.builder("AI_OUTPUT_FLAGGED", AuditOutcome.FAILED)
                    .entity("AiCompletion", purpose.name())
                    .newValues(finding)
                    .build());
            completion = scan.sanitized();
        }

        recordCall(envelope, prompt, completion, response);
        return completion;
    }

    private void recordCall(PromptEnvelope envelope, String prompt, String completion,
                            ChatResponse response) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("purpose", envelope.purpose().name());
        evidence.put("model", effectiveModel(envelope));
        evidence.put("promptChars", prompt.length());
        evidence.put("completionChars", completion.length());
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage != null && usage.getPromptTokens() != null) {
            evidence.put("promptTokens", usage.getPromptTokens());
        }
        if (usage != null && usage.getCompletionTokens() != null) {
            evidence.put("completionTokens", usage.getCompletionTokens());
        }
        audit.record(AuditEventSpec.builder("AI_CALL", AuditOutcome.SUCCESS)
                .entity("AiPrompt", envelope.purpose().name())
                .newValues(evidence)
                .build());
    }

    private void refuse(String action, PromptEnvelope envelope, Map<String, ?> why) {
        log.warn("AI prompt refused ({}): purpose={} {}", action, envelope.purpose(), why);
        audit.record(AuditEventSpec.builder(action, AuditOutcome.DENIED)
                .entity("AiPrompt", envelope.purpose().name())
                .newValues(why)
                .build());
    }

    private String effectiveModel(PromptEnvelope envelope) {
        return envelope.modelId() == null || envelope.modelId().isBlank()
                ? properties.getModelId() : envelope.modelId();
    }

    private void requireConfigured(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt cannot be empty.");
        }
        // Validated per call (not at startup) so the app can boot without a key
        // and only the LLM endpoints fail when it is missing.
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank() || UNSET_API_KEY.equals(apiKey)) {
            throw new AiNotConfiguredException(
                    "The AI assistant is not configured on this server. "
                            + "Set AIFILES_OPENAI_APIKEY and restart, or continue without AI "
                            + "features (mapping, validation and export all work without them).");
        }
    }
}
