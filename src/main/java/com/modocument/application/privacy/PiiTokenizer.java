package com.modocument.application.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenization proxy for the LLM features: sensitive values are replaced with
 * stable placeholders like {@code [EMAIL_1]} before a prompt leaves the
 * backend, and the placeholders are swapped back in the model's answer. The
 * model manipulates structure and placeholders; this class holds the only
 * mapping to the real values (per-request, in memory, never persisted).
 *
 * <p>Detection is heuristic, not NER: a field-name keyword list (names,
 * emails, phones, government IDs, addresses, birth dates, gender, pay) plus
 * value-shape patterns (email, phone, 9-digit SIN/SSN, 13-19 digit card) for
 * fields whose names carry no signal. Plain numbers and dates in
 * non-sensitive fields deliberately stay raw — reformatting them is the whole
 * utility of the cleaning/analysis features, and they are not identifying
 * once the identity fields around them are tokenized.
 */
@Service
public class PiiTokenizer {

    private static final Logger log = LoggerFactory.getLogger(PiiTokenizer.class);

    /** Ordered: specific categories first, the very broad "name" keywords last. */
    private static final List<Map.Entry<String, List<String>>> FIELD_KEYWORDS = List.of(
            Map.entry("EMAIL", List.of("email")),
            Map.entry("PHONE", List.of("phone", "mobile", "cell", "fax")),
            Map.entry("ID", List.of("nic", "nationalid", "sin", "ssn", "nino", "taxid",
                    "employeenumber", "employeeid",
                    "account", "iban", "swift", "creditcard", "cardnumber", "passport", "driverslicen")),
            Map.entry("DATE", List.of("dob", "birth")),
            Map.entry("ADDRESS", List.of("address", "street", "city", "postal", "zip")),
            Map.entry("GENDER", List.of("gender", "sex")),
            Map.entry("PAY", List.of("salary", "payrate", "wage", "compensation")),
            Map.entry("NAME", List.of("name", "initial", "surname")));

    /**
     * 13 or more digits (optionally spaced/dashed): card numbers, but also the
     * long account and reference numbers that carry the same risk. Unbounded on
     * purpose — a 20-digit run used to fall through every pattern here and go
     * to the model verbatim.
     */
    private static final Pattern CARD = Pattern.compile(
            "\\b\\d(?:[ -]?\\d){12,}(?!\\d)");
    private static final Pattern NINE_DIGIT_ID = Pattern.compile(
            "\\b\\d{3}[- ]?\\d{3}[- ]?\\d{3}\\b|\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b");
    private static final Pattern PHONE_LIKE = Pattern.compile(
            "(?<!\\d)(?:\\+?1[ .-]?)?(?:\\(\\d{3}\\)[ .-]?|\\d{3}[ .-]?)\\d{3}[ .-]?\\d{4}(?!\\d)");

    /**
     * Longest/most specific first so a card number is not half-eaten as a
     * phone. The email, NIC and bare-account shapes are {@link PiiPatterns}'
     * shared constants (ws13) — the tokenizer used to miss letter-prefixed
     * NIC formats (one letter + 9-14 digits + optional check letter) and
     * bare 9-12 digit account runs entirely; CARD / NINE_DIGIT_ID / PHONE
     * stay local because they cover the spaced and dashed spellings the log
     * patterns deliberately leave readable.
     */
    private static final List<Map.Entry<String, Pattern>> VALUE_PATTERNS = List.of(
            Map.entry("EMAIL", PiiPatterns.EMAIL),
            Map.entry("ID", PiiPatterns.NIC),
            Map.entry("ID", CARD),
            Map.entry("ID", NINE_DIGIT_ID),
            Map.entry("ID", PiiPatterns.ACCOUNT),
            Map.entry("PHONE", PHONE_LIKE));

    private static final Pattern TOKEN = Pattern.compile("\\[([A-Z]+)_(\\d+)]");

    private final PrivacyProperties properties;

    public PiiTokenizer(PrivacyProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isTokenizePii();
    }

    /** New per-request tokenization session. Not thread-safe; one per invocation. */
    public Session newSession() {
        return new Session(isEnabled());
    }

    /**
     * One-way pattern-only masking for free text (e.g. classification
     * snippets) where field names don't exist and nothing needs unmasking.
     */
    public String maskText(String text) {
        if (!isEnabled() || text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        for (Map.Entry<String, Pattern> entry : VALUE_PATTERNS) {
            masked = entry.getValue().matcher(masked).replaceAll("[" + entry.getKey() + "]");
        }
        return masked;
    }

    private Set<String> normalizedExtraFields() {
        Set<String> extras = new LinkedHashSet<>();
        for (String field : properties.getExtraSensitiveFields()) {
            String normalized = normalize(field);
            if (!normalized.isEmpty()) {
                extras.add(normalized);
            }
        }
        return extras;
    }

    private static String normalize(String fieldName) {
        return fieldName == null
                ? ""
                : fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Bidirectional token map with a stable token per distinct (category, value). */
    public final class Session {

        private final boolean enabled;
        private final Set<String> extraFields;
        private final Map<String, String> valueToToken = new HashMap<>();
        private final Map<String, String> tokenToValue = new HashMap<>();
        private final Map<String, Integer> counters = new HashMap<>();

        private Session(boolean enabled) {
            this.enabled = enabled;
            this.extraFields = enabled ? normalizedExtraFields() : Set.of();
        }

        public String tokenizeValue(String fieldName, String value) {
            if (!enabled || value == null || value.isBlank()) {
                return value;
            }
            // A raw value that already looks like one of our tokens gets a
            // token of its own. Sending it as-is would make detokenization
            // hand back another row's real value in its place; LITERAL round
            // trips it back to exactly what the cell contained.
            if (TOKEN.matcher(value.trim()).matches()) {
                log.debug("Value in field '{}' matches the token grammar; passing it as a literal.",
                        fieldName);
                return tokenFor("LITERAL", value);
            }

            String category = categoryForField(fieldName);
            if (category != null) {
                return tokenFor(category, value);
            }
            for (Map.Entry<String, Pattern> entry : VALUE_PATTERNS) {
                if (entry.getValue().matcher(value.trim()).matches()) {
                    return tokenFor(entry.getKey(), value);
                }
            }
            return replaceEmbedded(value);
        }

        public Map<String, String> tokenizeRow(Map<String, String> row) {
            if (!enabled) {
                return row;
            }
            Map<String, String> tokenized = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                tokenized.put(entry.getKey(), tokenizeValue(entry.getKey(), entry.getValue()));
            }
            return tokenized;
        }

        public List<Map<String, String>> tokenizeRows(List<Map<String, String>> rows) {
            return enabled ? rows.stream().map(this::tokenizeRow).toList() : rows;
        }

        /**
         * Reversible masking for free text (a document's extracted content),
         * where there are no field names to reason about: the value-shape
         * patterns are applied in place and the tokens can be swapped back in
         * the model's answer. Names and addresses in prose carry no detectable
         * shape and still travel — see the class comment.
         */
        public String tokenizeText(String text) {
            if (!enabled || text == null || text.isBlank()) {
                return text;
            }
            return replaceEmbedded(text);
        }

        /** Single pass: restored values are never re-scanned; unknown tokens stay verbatim. */
        public String detokenize(String text) {
            if (!enabled || text == null || tokenToValue.isEmpty()) {
                return text;
            }
            Matcher matcher = TOKEN.matcher(text);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String original = tokenToValue.get(matcher.group());
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(original == null ? matcher.group() : original));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        public Map<String, String> detokenizeRow(Map<String, String> row) {
            if (!enabled) {
                return row;
            }
            Map<String, String> restored = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                restored.put(entry.getKey(), detokenize(entry.getValue()));
            }
            return restored;
        }

        public List<Map<String, String>> detokenizeRows(List<Map<String, String>> rows) {
            return enabled ? rows.stream().map(this::detokenizeRow).toList() : rows;
        }

        private String categoryForField(String fieldName) {
            String normalized = normalize(fieldName);
            if (normalized.isEmpty()) {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : FIELD_KEYWORDS) {
                for (String keyword : entry.getValue()) {
                    if (normalized.contains(keyword)) {
                        return entry.getKey();
                    }
                }
            }
            for (String extra : extraFields) {
                if (normalized.contains(extra)) {
                    return "VALUE";
                }
            }
            return null;
        }

        /** Sensitive shapes embedded in otherwise free-text cells (e.g. a Notes column). */
        private String replaceEmbedded(String value) {
            String result = value;
            for (Map.Entry<String, Pattern> entry : VALUE_PATTERNS) {
                Matcher matcher = entry.getValue().matcher(result);
                StringBuilder out = new StringBuilder();
                boolean found = false;
                while (matcher.find()) {
                    found = true;
                    matcher.appendReplacement(out,
                            Matcher.quoteReplacement(tokenFor(entry.getKey(), matcher.group())));
                }
                if (found) {
                    matcher.appendTail(out);
                    result = out.toString();
                }
            }
            return result;
        }

        private String tokenFor(String category, String value) {
            return valueToToken.computeIfAbsent(category + '\0' + value, key -> {
                int n = counters.merge(category, 1, Integer::sum);
                String token = "[" + category + "_" + n + "]";
                tokenToValue.put(token, value);
                return token;
            });
        }
    }
}
