package com.modocument.infrastructure.ai;

import com.modocument.application.privacy.PiiPatterns;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate C10: every completion the AI gateway receives is scanned BEFORE it is
 * returned (and therefore before any detokenization at the call site).
 *
 * <p>Two families of findings:
 * <ul>
 *   <li><b>Identifier leak</b> — a raw NIC/account/email shape in the output.
 *       Requests carry only {@code [ID_1]}-style tokens (the gateway refuses
 *       raw shapes on the way out), so an identifier shape in the completion
 *       is model-invented or model-recalled; either way it was never the
 *       user's data and must not be written anywhere. A shape that literally
 *       appeared in the request text is exempt (it passed the request scan,
 *       so it is not an identifier by our patterns' own judgement).</li>
 *   <li><b>Instruction echo / injection</b> — the completion repeats
 *       formatting instructions ({@code LlmStructuredJsonParser}'s
 *       signatures) or carries prompt-injection language (ignore/disregard
 *       instructions, system-prompt talk, jailbreak/role-play shapes).</li>
 * </ul>
 */
@Component
public class AiOutputScanner {

    /** Identifier shapes scanned for, in scan order. */
    private static final Map<String, Pattern> IDENTIFIER_SHAPES = Map.of(
            "NIC", PiiPatterns.NIC,
            "ACCOUNT", PiiPatterns.ACCOUNT,
            "EMAIL", PiiPatterns.EMAIL);

    /**
     * Injection/echo language that has no business in a data completion.
     * Deliberately narrow — completions are JSON tables, mappings and short
     * prose, where none of these phrasings occur legitimately.
     */
    private static final List<Pattern> INJECTION_SHAPES = List.of(
            Pattern.compile("(?i)\\b(ignore|disregard|forget)\\b[^.\\n]{0,60}\\b(instructions?|prompts?|rules)\\b"),
            Pattern.compile("(?i)\\bsystem\\s+prompt\\b"),
            Pattern.compile("(?i)\\bjail\\s*break|jailbroken\\b"),
            Pattern.compile("(?i)\\bdeveloper\\s+mode\\b"),
            Pattern.compile("(?i)\\byou are now\\b[^.\\n]{0,80}\\b(unrestricted|free of|no longer bound)\\b"),
            Pattern.compile("(?i)\\b(act|role[- ]?play)\\b[^.\\n]{0,40}\\bwithout (any )?(restrictions?|limitations?)\\b"));

    /** What the scan found; {@code sanitized} has identifier shapes masked. */
    public record ScanResult(Set<String> identifierCategories, boolean instructionEcho,
                             String sanitized) {

        public boolean clean() {
            return identifierCategories.isEmpty() && !instructionEcho;
        }
    }

    /**
     * @param completion      the raw model output (pre-detokenization)
     * @param requestText     the assembled prompt that produced it
     * @param scanIdentifiers false for outputs that ARE customer text
     *                        (vision OCR), where identifier shapes are the
     *                        document's own content
     */
    public ScanResult scan(String completion, String requestText, boolean scanIdentifiers) {
        if (completion == null || completion.isBlank()) {
            return new ScanResult(Set.of(), false, completion);
        }
        Set<String> categories = new LinkedHashSet<>();
        String sanitized = completion;
        if (scanIdentifiers) {
            String request = requestText == null ? "" : requestText;
            for (Map.Entry<String, Pattern> shape : IDENTIFIER_SHAPES.entrySet()) {
                Matcher matcher = shape.getValue().matcher(completion);
                boolean found = false;
                while (matcher.find()) {
                    if (!request.contains(matcher.group())) {
                        found = true;
                    }
                }
                if (found) {
                    categories.add(shape.getKey());
                    sanitized = shape.getValue().matcher(sanitized)
                            .replaceAll(Matcher.quoteReplacement(PiiPatterns.MASK));
                }
            }
        }
        boolean echo = LlmStructuredJsonParser.looksLikeInstructionEcho(completion)
                || INJECTION_SHAPES.stream().anyMatch(p -> p.matcher(completion).find());
        return new ScanResult(Set.copyOf(categories), echo, sanitized);
    }
}
