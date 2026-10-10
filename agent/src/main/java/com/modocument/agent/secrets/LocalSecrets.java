package com.modocument.agent.secrets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The bank's credentials for its own systems — held ONLY here, on the
 * bank's host. MoDocument stores just the {@code secretRef} name of a
 * connection; the agent resolves it locally, in this order:
 * <ol>
 *   <li>environment variable {@code MODOCUMENT_SECRET_<REF>} (REF upper-cased,
 *       every character outside A-Z/0-9 replaced by {@code _});</li>
 *   <li>{@code <secretsDir>/<ref>} as a FILE (Docker/Kubernetes secret
 *       mount);</li>
 *   <li>{@code <secretsDir>/<ref>/} as a FOLDER with one file per key
 *       ({@code password}, {@code token}, {@code apiKey},
 *       {@code privateKeyPem}, …).</li>
 * </ol>
 * A value (env or file) that is a JSON object provides several keys
 * ({@code {"password": "…"}}); anything else is a single secret under the
 * key {@code value}, which connectors accept wherever they need one secret.
 * {@link Secret#toString()} never prints a value.
 */
public class LocalSecrets {

    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final Path dir;
    private final Function<String, String> env;
    private final ObjectMapper mapper;

    public LocalSecrets(Path dir, Function<String, String> env, ObjectMapper mapper) {
        this.dir = dir;
        this.env = env;
        this.mapper = mapper;
    }

    /** The secret named {@code ref}; empty for a null ref. */
    public Secret resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return Secret.EMPTY;
        }
        if (!REF.matcher(ref).matches() || ref.contains("..")) {
            throw new IllegalArgumentException("The secretRef is not a plain name.");
        }
        String fromEnv = env.apply("MODOCUMENT_SECRET_"
                + ref.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_"));
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return parse(fromEnv);
        }
        Path path = dir.resolve(ref).normalize();
        if (!path.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("The secretRef is not a plain name.");
        }
        try {
            if (Files.isRegularFile(path)) {
                return parse(Files.readString(path, StandardCharsets.UTF_8));
            }
            if (Files.isDirectory(path)) {
                Map<String, String> values = new LinkedHashMap<>();
                try (Stream<Path> files = Files.list(path)) {
                    for (Path file : files.toList()) {
                        String key = file.getFileName().toString();
                        if (Files.isRegularFile(file) && KEY.matcher(key).matches()) {
                            values.put(key, strip(Files.readString(file, StandardCharsets.UTF_8)));
                        }
                    }
                }
                return new Secret(values);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The local secret '" + ref + "' could not be read.", e);
        }
        throw new IllegalArgumentException("No local secret named '" + ref + "' (set "
                + "MODOCUMENT_SECRET_<REF> or add it to the secrets folder).");
    }

    private Secret parse(String raw) {
        String value = strip(raw);
        if (value.startsWith("{")) {
            try {
                return new Secret(mapper.readValue(value, STRING_MAP));
            } catch (IOException e) {
                throw new IllegalArgumentException("A local secret looks like JSON but is not "
                        + "a flat object of strings.");
            }
        }
        return new Secret(Map.of("value", value));
    }

    /** Trailing newlines from `echo` / editors are not part of a secret. */
    private static String strip(String raw) {
        String value = raw;
        while (value.endsWith("\n") || value.endsWith("\r")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** Secret values for one call; never printed. */
    public record Secret(Map<String, String> values) {

        public static final Secret EMPTY = new Secret(Map.of());

        public Secret {
            values = values == null ? Map.of() : Map.copyOf(values);
        }

        /** {@code key}, else the single {@code value}. */
        public String get(String key) {
            String exact = values.get(key);
            return exact != null ? exact : values.get("value");
        }

        /** Every value (for redaction). */
        public Collection<String> all() {
            return values.values();
        }

        @Override
        public String toString() {
            return "Secret" + values.keySet() + " (values redacted)";
        }
    }
}
