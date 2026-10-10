package com.modocument.release;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ws24 version single source: runs scripts/check-version-sync.sh and
 * scripts/bump-version.sh against a temporary copy of the four version
 * carriers (pom.xml, frontend/package.json + lock, CHANGELOG.md), so the
 * real tree is never touched. Skipped where no bash exists.
 */
class VersionScriptsTest {

    private static final Path REPO = Path.of("").toAbsolutePath();
    private static final List<String> CARRIERS = List.of(
            "pom.xml", "agent/pom.xml", "CHANGELOG.md", "frontend/package.json", "frontend/package-lock.json",
            "scripts/version-lib.sh", "scripts/check-version-sync.sh", "scripts/bump-version.sh");

    @TempDir
    Path tree;

    private String current;

    @BeforeAll
    static void requireBash() {
        assumeTrue(run(REPO, Map.of(), "bash", "-c", "true").exitCode() == 0, "bash not available");
    }

    @BeforeEach
    void copyTree() throws IOException {
        for (String file : CARRIERS) {
            Path target = tree.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(REPO.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
        }
        current = pomVersion(Files.readString(tree.resolve("pom.xml")));
    }

    @Test
    void theRepositoryIsInSync() {
        Result result = check();
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("version-sync: OK " + current);
    }

    @Test
    void aMatchingReleaseOrCandidateTagPassesAnyOtherFails() {
        assertThat(check("v" + current).exitCode()).isZero();
        assertThat(check("v" + current + "-rc.1").exitCode()).isZero();
        assertThat(check("v" + current + "-rc.12").exitCode()).isZero();

        for (String bad : new String[] {"v9.9.9", current, "v" + current + "-rc.0", "v" + current + "-beta.1",
                "v" + current + "-rc.1x", "release-" + current}) {
            Result result = check(bad);
            assertThat(result.exitCode()).as("tag %s: %s", bad, result.output()).isEqualTo(1);
        }
    }

    @Test
    void driftInAnyCarrierFails() throws IOException {
        replace("frontend/package.json", "\"version\": \"" + current + "\"", "\"version\": \"9.9.9\"");
        Result result = check();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("frontend/package.json version '9.9.9'");
    }

    @Test
    void driftInTheAgentPomFails() throws IOException {
        replace("agent/pom.xml", "<version>" + current + "</version>", "<version>9.9.9</version>");
        Result result = check();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("agent/pom.xml version '9.9.9'");
    }

    @Test
    void driftInTheLockfileFails() throws IOException {
        Path lock = tree.resolve("frontend/package-lock.json");
        List<String> lines = new ArrayList<>(Files.readAllLines(lock));
        int root = lines.indexOf("    \"\": {");
        for (int i = root; i < lines.size(); i++) {
            if (lines.get(i).startsWith("      \"version\": ")) {
                lines.set(i, "      \"version\": \"0.0.1\",");
                break;
            }
        }
        Files.write(lock, lines);

        Result result = check();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("package-lock.json version '0.0.1'");
    }

    @Test
    void aChangelogWithoutUnreleasedOrWithAnUndatedReleaseFails() throws IOException {
        replace("CHANGELOG.md", "## [Unreleased]", "## Upcoming");
        assertThat(check().output()).contains("exactly one '## [Unreleased]'");

        copyTree();
        replace("CHANGELOG.md", "## [" + current + "] - ", "## [" + current + "] ");
        assertThat(check().output()).contains("must read '## [X.Y.Z] - YYYY-MM-DD'");
    }

    @Test
    void bumpMovesEveryCarrierAndTheUnreleasedNotes() throws IOException {
        String next = nextMinor(current);
        String pomBefore = Files.readString(tree.resolve("pom.xml"));

        Result bump = run(tree, Map.of("RELEASE_DATE", "2031-02-03"),
                "bash", "scripts/bump-version.sh", next);
        assertThat(bump.exitCode()).as(bump.output()).isZero();

        String pom = Files.readString(tree.resolve("pom.xml"));
        assertThat(pomVersion(pom)).isEqualTo(next);
        // Only the project version moved: parent, dependency and plugin
        // versions are untouched.
        assertThat(pom.replaceFirst(Pattern.quote("<version>" + next + "</version>"),
                Matcher.quoteReplacement("<version>" + current + "</version>")))
                .isEqualTo(pomBefore);
        // ws26: the Connector Agent ships with the same release number.
        assertThat(Files.readString(tree.resolve("agent/pom.xml")))
                .contains("<artifactId>modocument-agent</artifactId>\n    <version>" + next + "</version>");
        assertThat(Files.readString(tree.resolve("frontend/package.json")))
                .contains("\"version\": \"" + next + "\"");
        String changelog = Files.readString(tree.resolve("CHANGELOG.md"));
        assertThat(changelog).contains("## [Unreleased]\n\n## [" + next + "] - 2031-02-03\n");
        assertThat(changelog).contains("compare/v" + next + "...HEAD");
        assertThat(check("v" + next + "-rc.1").exitCode()).isZero();

        // A second bump needs new [Unreleased] entries and a higher version.
        Result again = run(tree, Map.of(), "bash", "scripts/bump-version.sh", nextMinor(next));
        assertThat(again.exitCode()).isEqualTo(1);
        assertThat(again.output()).contains("[Unreleased] has no entries");
        Result lower = run(tree, Map.of(), "bash", "scripts/bump-version.sh", current);
        assertThat(lower.exitCode()).isEqualTo(1);
        assertThat(lower.output()).contains("must be greater than");
    }

    @Test
    void bumpRejectsMalformedInput() {
        assertThat(run(tree, Map.of(), "bash", "scripts/bump-version.sh", "v1.2.3").exitCode()).isEqualTo(2);
        assertThat(run(tree, Map.of(), "bash", "scripts/bump-version.sh").exitCode()).isEqualTo(2);
        assertThat(run(tree, Map.of("RELEASE_DATE", "tomorrow"), "bash", "scripts/bump-version.sh",
                nextMinor(current)).exitCode()).isEqualTo(2);
    }

    // ------------------------------------------------------------ helpers

    private Result check(String... tag) {
        List<String> command = new ArrayList<>(List.of("bash", "scripts/check-version-sync.sh"));
        command.addAll(List.of(tag));
        return run(tree, Map.of(), command.toArray(String[]::new));
    }

    private void replace(String file, String from, String to) throws IOException {
        Path path = tree.resolve(file);
        String content = Files.readString(path);
        assertThat(content).contains(from);
        Files.writeString(path, content.replace(from, to));
    }

    private static String pomVersion(String pom) {
        String afterParent = pom.substring(pom.indexOf("</parent>"));
        int start = afterParent.indexOf("<version>") + "<version>".length();
        return afterParent.substring(start, afterParent.indexOf("</version>", start));
    }

    private static String nextMinor(String version) {
        String[] parts = version.split("\\.");
        return parts[0] + "." + (Integer.parseInt(parts[1]) + 1) + ".0";
    }

    record Result(int exitCode, String output) {
    }

    private static Result run(Path dir, Map<String, String> env, String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
            builder.environment().putAll(env);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output + "\n(timed out)");
            }
            return new Result(process.exitValue(), output);
        } catch (IOException e) {
            return new Result(-1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, e.toString());
        }
    }
}
