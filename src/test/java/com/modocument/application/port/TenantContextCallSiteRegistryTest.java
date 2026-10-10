package com.modocument.application.port;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No unreviewed cross-tenant path (plan §2): every class under
 * {@code src/main/java} that establishes a tenant context —
 * {@code TenantContext.runAsSystem(}, {@code runAs(}, {@code runWith(} or
 * {@code open(} — must be listed in the CALL-SITE REGISTRY of the
 * {@link TenantContext} Javadoc, and every class the registry names must
 * still make such a call. A {@code Class#method} entry must name a method
 * that the class still declares.
 *
 * <p>The scan ignores comments and string literals, so a Javadoc example
 * such as {@code TenantContext.runAs(bankId, ...)} is not a call site.
 * Adding a call site therefore means adding a registry entry
 * ({@code <li>{@code Class#method} — why}) for the Supervisor's review.
 */
class TenantContextCallSiteRegistryTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path TENANT_CONTEXT = MAIN_SOURCES.resolve(
            Path.of("com", "modocument", "application", "port", "TenantContext.java"));

    static final Pattern CALL = Pattern.compile(
            "\\bTenantContext\\s*\\.\\s*(runAsSystem|runAs|runWith|open)\\s*\\(");
    private static final Pattern CODE_REF = Pattern.compile(
            "\\{@code\\s+([A-Z]\\w*)?(?:#(\\w+))?\\s*}");

    @Test
    void everyCallSiteIsRegisteredAndEveryRegisteredClassStillCalls() throws IOException {
        assertThat(TENANT_CONTEXT).isRegularFile(); // surefire runs in the project basedir

        Map<String, Set<String>> registry = parseRegistry(Files.readString(TENANT_CONTEXT));
        Map<String, Path> sources = mainSources();
        Map<String, Set<String>> callers = new TreeMap<>();
        sources.forEach((className, file) -> {
            if (!file.equals(TENANT_CONTEXT)) {
                Set<String> kinds = callKinds(read(file));
                if (!kinds.isEmpty()) {
                    callers.put(className, kinds);
                }
            }
        });

        assertThat(callers).as("the scan must find the known call sites").isNotEmpty();

        Set<String> unregistered = new TreeSet<>(callers.keySet());
        unregistered.removeAll(registry.keySet());
        assertThat(unregistered)
                .as("classes calling TenantContext.runAs/runAsSystem/runWith/open that the "
                        + "TenantContext Javadoc CALL-SITE REGISTRY does not list; add an "
                        + "entry ({@code Class#method} — why) for review. Calls found: %s", callers)
                .isEmpty();

        Set<String> stale = new TreeSet<>(registry.keySet());
        stale.removeAll(callers.keySet());
        assertThat(stale)
                .as("registry entries naming a class that no longer establishes a tenant "
                        + "context (or no longer exists); remove or fix the entry")
                .isEmpty();

        List<String> missingMethods = new ArrayList<>();
        registry.forEach((className, methods) -> {
            String source = stripCommentsAndStrings(read(sources.get(className)));
            for (String method : methods) {
                if (!declaresMethod(source, method)) {
                    missingMethods.add(className + "#" + method);
                }
            }
        });
        assertThat(missingMethods)
                .as("registry entries naming a method the class no longer declares")
                .isEmpty();
    }

    @Test
    void theScanIgnoresCommentsAndStringsButSeesRealCalls() {
        String source = """
                /** Runs inside {@code TenantContext.runAs(bankId, ...)} — a comment. */
                class Example {
                    // TenantContext.runAsSystem(() -> x) in a line comment
                    String s = "TenantContext.open(1)";
                    String block = \"""
                            TenantContext.runWith(snapshot, task)
                            \""";
                    char c = '"';
                    void work() {
                        TenantContext . runAsSystem(() -> read());
                        TenantContext.runAs(7, this::write);
                    }
                }
                """;

        assertThat(callKinds(source)).containsExactlyInAnyOrder("runAsSystem", "runAs");
        assertThat(callKinds("class A { /* TenantContext.runAs(1, r) */ }")).isEmpty();
        assertThat(declaresMethod(stripCommentsAndStrings(source), "work")).isTrue();
        assertThat(declaresMethod(stripCommentsAndStrings(source), "read")).isFalse();
    }

    @Test
    void theRegistryParserReadsTheEntryHeadsOnly() {
        String javadoc = """
                /**
                 * <p>CALL-SITE REGISTRY — every place:
                 * <ul>
                 *   <li>{@code Alpha#one} / {@code #two} — uses {@code Gamma} in passing.</li>
                 *   <li>{@code Beta} (startup) — {@code runAsSystem}.</li>
                 * </ul>
                 */
                """;

        Map<String, Set<String>> registry = parseRegistry(javadoc);

        assertThat(registry).containsOnlyKeys("Alpha", "Beta");
        assertThat(registry.get("Alpha")).containsExactlyInAnyOrder("one", "two");
        assertThat(registry.get("Beta")).isEmpty();
    }

    /**
     * Class → named methods, from each {@code <li>}'s head (the text before
     * its em dash): {@code {@code Class#method}} names a class and method,
     * {@code {@code #method}} another method of the entry's last class.
     */
    static Map<String, Set<String>> parseRegistry(String tenantContextSource) {
        int start = tenantContextSource.indexOf("CALL-SITE REGISTRY");
        assertThat(start).as("TenantContext Javadoc has a CALL-SITE REGISTRY").isNotNegative();
        int end = tenantContextSource.indexOf("</ul>", start);
        String block = tenantContextSource.substring(start, end)
                .replaceAll("(?m)^\\s*\\*\\s?", " ");

        Map<String, Set<String>> registry = new LinkedHashMap<>();
        String[] items = block.split("<li>");
        for (int i = 1; i < items.length; i++) {
            String item = items[i];
            int dash = item.indexOf('—');
            assertThat(dash)
                    .as("registry entry must read '{@code Class#method} — why': %s", item.trim())
                    .isPositive();
            String head = item.substring(0, dash);
            String currentClass = null;
            Matcher ref = CODE_REF.matcher(head);
            while (ref.find()) {
                if (ref.group(1) != null) {
                    currentClass = ref.group(1);
                    registry.computeIfAbsent(currentClass, k -> new TreeSet<>());
                }
                if (ref.group(2) != null) {
                    assertThat(currentClass)
                            .as("'{@code #%s}' needs a class before it: %s", ref.group(2), head.trim())
                            .isNotNull();
                    registry.get(currentClass).add(ref.group(2));
                }
            }
            assertThat(currentClass).as("registry entry names no class: %s", item.trim()).isNotNull();
        }
        return registry;
    }

    /** The TenantContext methods the source calls, ignoring comments and literals. */
    static Set<String> callKinds(String source) {
        Set<String> kinds = new TreeSet<>();
        Matcher call = CALL.matcher(stripCommentsAndStrings(source));
        while (call.find()) {
            kinds.add(call.group(1));
        }
        return kinds;
    }

    /** A declaration {@code name(...) [throws ...] {}, not a call. */
    static boolean declaresMethod(String strippedSource, String name) {
        return Pattern.compile("[\\w>\\]]\\s+" + Pattern.quote(name)
                        + "\\s*\\((?:[^;{}()]|\\([^()]*\\))*\\)\\s*(?:throws[^{;]+)?\\{")
                .matcher(strippedSource).find();
    }

    /**
     * Blanks out comments, string/char literals and text blocks so that only
     * code is scanned. Line breaks are kept.
     */
    static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                int stop = close < 0 ? n : close + 2;
                keepNewlines(source, i, stop, out);
                i = stop;
            } else if (source.startsWith("\"\"\"", i)) {
                int close = source.indexOf("\"\"\"", i + 3);
                int stop = close < 0 ? n : close + 3;
                keepNewlines(source, i, stop, out);
                out.append("\"\"");
                i = stop;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(c).append(c);
                i = Math.min(n, j + 1);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void keepNewlines(String source, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            if (source.charAt(k) == '\n') {
                out.append('\n');
            }
        }
        out.append(' ');
    }

    /**
     * Top-level class simple name → file, for the classes that call
     * TenantContext (the registry names classes by simple name, so two
     * such classes may not share one) plus every other class, for the
     * method look-up of a registry entry.
     */
    private static Map<String, Path> mainSources() throws IOException {
        Map<String, Path> callers = new TreeMap<>();
        Map<String, Path> others = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = path.getFileName().toString().replace(".java", "");
                if (callKinds(read(path)).isEmpty()) {
                    others.putIfAbsent(name, path);
                } else {
                    Path previous = callers.put(name, path);
                    assertThat(previous)
                            .as("two classes calling TenantContext share the simple name %s: %s",
                                    name, path)
                            .isNull();
                }
            }
        }
        others.putAll(callers);
        return others;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
