package com.modocument.application.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * THE password acceptance rule (SEC-021, gate B4), enforced everywhere a
 * password is set: password change (PUT /api/auth/password), admin user
 * creation ({@code AuthUseCase.createUser}, the ws09 seam) and the bootstrap
 * admin. A password is rejected when it
 * <ul>
 *   <li>is shorter than the configured minimum (default 12 — NIST 800-63B
 *       leans on length, not composition rules; no upper cap is imposed
 *       beyond the request DTOs' 200-char schema bound, so long passphrases
 *       are first-class),</li>
 *   <li>contains the owner's email local-part (case-insensitive, only when
 *       the local-part is 3+ chars so initials don't poison the check), or</li>
 *   <li>appears in the embedded breached/common list
 *       ({@code security/common-passwords.txt}, ~11k entries loaded once
 *       into a set and matched case-insensitively — 'Password123' and
 *       friends die here).</li>
 * </ul>
 * Deliberately NO composition rules (mandatory digits/symbols): they push
 * users toward 'Password123!' patterns, which the breached list rejects
 * anyway. Error messages never echo the password.
 */
@Component
public class PasswordPolicy {

    private static final String BREACHED_LIST_RESOURCE = "/security/common-passwords.txt";

    /** Email local-parts shorter than this are not substring-checked. */
    private static final int MIN_LOCAL_PART_LENGTH = 3;

    private static final Set<String> BREACHED = loadBreachedList();

    private final int minLength;

    public PasswordPolicy(
            @Value("${modocument.security.password.min-length:12}") int minLength) {
        if (minLength < 1) {
            throw new IllegalStateException("modocument.security.password.min-length must be positive.");
        }
        this.minLength = minLength;
    }

    /**
     * @param password the candidate password
     * @param email the owning account's email (its local-part must not appear
     *     in the password); null/blank skips that check
     * @throws IllegalArgumentException when the password is unacceptable; the
     *     message is safe to return to the caller (no values echoed)
     */
    public void validate(String password, String email) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException(
                    "Password must be at least " + minLength + " characters.");
        }
        String lowered = password.toLowerCase(Locale.ROOT);
        // Breached beats short: "Password123" should be called what it is,
        // not nudged toward "Password123!" by a length complaint.
        if (BREACHED.contains(lowered)) {
            throw new IllegalArgumentException(
                    "This password appears in lists of commonly used or breached "
                            + "passwords. Choose a different one.");
        }
        if (password.length() < minLength) {
            throw new IllegalArgumentException(
                    "Password must be at least " + minLength + " characters.");
        }
        String localPart = emailLocalPart(email);
        if (localPart != null && localPart.length() >= MIN_LOCAL_PART_LENGTH
                && lowered.contains(localPart)) {
            throw new IllegalArgumentException(
                    "Password must not contain your email address.");
        }
    }

    public int minLength() {
        return minLength;
    }

    private static String emailLocalPart(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        int at = trimmed.indexOf('@');
        return at < 0 ? trimmed : trimmed.substring(0, at);
    }

    private static Set<String> loadBreachedList() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(BREACHED_LIST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Breached-password list " + BREACHED_LIST_RESOURCE + " is missing "
                                + "from the classpath.");
            }
            Set<String> entries = new HashSet<>(16_384);
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String candidate = line.trim();
                    if (candidate.isEmpty() || candidate.startsWith("#")) {
                        continue;
                    }
                    entries.add(candidate.toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + BREACHED_LIST_RESOURCE, e);
        }
    }
}
