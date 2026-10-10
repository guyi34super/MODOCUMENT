package com.modocument.application.port;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the AI gateway needs to accept a prompt (gates C8/C9): the
 * {@link AiPurpose}, the tenant the prompt acts for, the user it acts on
 * behalf of, and the prompt text as parts that each carry the bank their
 * content came from. Call sites tokenize sensitive values (PiiTokenizer)
 * BEFORE building an envelope; the gateway re-scans the assembled text and
 * refuses anything that still carries a raw identifier shape, any part whose
 * source bank differs from the envelope's bank, and any call whose bound
 * {@code TenantContext} is missing or different — the code-level guarantee
 * that no prompt ever mixes two banks' data.
 *
 * <p>The envelope's bank defaults to the tenant bound on the building thread
 * ({@link TenantContext#currentBankId()}); a call site that knows the owning
 * entity's bank pins it explicitly with {@link Builder#bank(Integer)} so a
 * context/entity mismatch is refused too.
 */
public final class PromptEnvelope {

    /** One piece of prompt text and the bank its content belongs to. */
    public record Part(String text, Integer bankId) {
    }

    private final AiPurpose purpose;
    private final Integer bankId;
    private final Integer userId;
    private final String modelId;
    private final List<Part> parts;

    private PromptEnvelope(Builder builder) {
        this.purpose = builder.purpose;
        this.bankId = builder.bankId;
        this.userId = builder.userId;
        this.modelId = builder.modelId;
        this.parts = List.copyOf(builder.parts);
    }

    /** Single-part envelope for the current tenant. */
    public static PromptEnvelope of(AiPurpose purpose, String text) {
        return builder(purpose).part(text).build();
    }

    /** Single-part envelope for the current tenant with a per-call model. */
    public static PromptEnvelope of(AiPurpose purpose, String text, String modelId) {
        return builder(purpose).part(text).model(modelId).build();
    }

    public static Builder builder(AiPurpose purpose) {
        return new Builder(purpose);
    }

    public AiPurpose purpose() {
        return purpose;
    }

    public Integer bankId() {
        return bankId;
    }

    public Integer userId() {
        return userId;
    }

    /** Per-call model id; null/blank means the configured default model. */
    public String modelId() {
        return modelId;
    }

    public List<Part> parts() {
        return parts;
    }

    /** The assembled prompt text the gateway sends (parts in order). */
    public String text() {
        if (parts.size() == 1) {
            return parts.get(0).text() == null ? "" : parts.get(0).text();
        }
        StringBuilder joined = new StringBuilder();
        for (Part part : parts) {
            if (part.text() != null) {
                joined.append(part.text());
            }
        }
        return joined.toString();
    }

    public static final class Builder {

        private final AiPurpose purpose;
        private Integer bankId = TenantContext.currentBankId().orElse(null);
        private Integer userId;
        private String modelId;
        private final List<Part> parts = new ArrayList<>();

        private Builder(AiPurpose purpose) {
            if (purpose == null) {
                throw new IllegalArgumentException("A prompt envelope needs a purpose.");
            }
            this.purpose = purpose;
        }

        /**
         * Pins the envelope's bank (e.g. the owning entity's BankId). A null
         * or non-positive id means "unstamped legacy object" (same contract
         * as {@link TenantContext#currentBankId()}) and keeps the bound
         * tenant default instead of erasing it.
         */
        public Builder bank(Integer bankId) {
            if (bankId != null && bankId > 0) {
                this.bankId = bankId;
            }
            return this;
        }

        public Builder user(Integer userId) {
            this.userId = userId;
            return this;
        }

        public Builder model(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /** A part whose content belongs to the envelope's bank. */
        public Builder part(String text) {
            parts.add(new Part(text, null));
            return this;
        }

        /**
         * A part whose content came from an entity stamped with
         * {@code bankId}; a null or non-positive stamp means unstamped and
         * the part is treated as the envelope's own bank.
         */
        public Builder part(String text, Integer bankId) {
            parts.add(new Part(text, bankId != null && bankId > 0 ? bankId : null));
            return this;
        }

        public PromptEnvelope build() {
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("A prompt envelope needs at least one part.");
            }
            return new PromptEnvelope(this);
        }
    }
}
