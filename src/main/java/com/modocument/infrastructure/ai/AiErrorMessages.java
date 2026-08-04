package com.modocument.infrastructure.ai;

import org.springframework.ai.retry.NonTransientAiException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps raw Spring AI / provider errors to operator-friendly text. */
public final class AiErrorMessages {

    private static final Pattern JSON_MESSAGE =
            Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private AiErrorMessages() {
    }

    public static String friendlyMessage(Throwable error) {
        if (error instanceof AiNotConfiguredException configured) {
            return configured.getMessage();
        }
        if (error instanceof NonTransientAiException ai) {
            return friendlyMessage(ai.getMessage());
        }
        return error.getMessage() == null ? "Unknown error" : error.getMessage();
    }

    public static String friendlyMessage(String raw) {
        if (raw == null) {
            return "AI provider request failed.";
        }
        if (raw.contains("402") || raw.toLowerCase().contains("credits")) {
            if (raw.toLowerCase().contains("inference providers")
                    || raw.toLowerCase().contains("monthly included credits")) {
                return "AI provider credits are insufficient. Add Hugging Face Inference "
                        + "Provider credits at https://huggingface.co/settings/billing "
                        + "or choose a smaller model.";
            }
            if (raw.toLowerCase().contains("openrouter")) {
                return "AI provider credits are insufficient. Add credits to your OpenRouter "
                        + "account or ask for a smaller edit.";
            }
            return "AI provider credits are insufficient. Add credits to your inference "
                    + "provider account or use a smaller/cheaper model.";
        }
        if (raw.contains("401") || raw.toLowerCase().contains("unauthorized")
                || raw.toLowerCase().contains("user not found")) {
            return "AI provider rejected the API key. Verify AIFILES_OPENAI_APIKEY matches "
                    + "your configured provider (AIFILES_OPENAI_BASEURL) and restart the app.";
        }
        Matcher matcher = JSON_MESSAGE.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        if (raw.startsWith("HTTP ")) {
            int dash = raw.indexOf(" - ");
            if (dash > 0 && dash + 3 < raw.length()) {
                return raw.substring(dash + 3);
            }
        }
        return raw.isBlank() ? "AI provider request failed." : raw;
    }
}
