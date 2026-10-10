package com.modocument.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ws24 release scripts (scripts/release/*.sh, run by release.yml and
 * deploy.yml), exercised locally with stand-ins for the AWS CLI and skopeo
 * on the PATH and a loopback HTTP server for the probe — the parts that can
 * be proven without an AWS account: digest pinning of the task definition,
 * rollback detection, immutable-tag handling, the smoke test and the release
 * notes extraction. Skipped where bash, jq or curl are missing.
 */
class ReleaseScriptsTest {

    private static final Path REPO = Path.of("").toAbsolutePath();
    private static final Path SCRIPTS = REPO.resolve("scripts/release");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);
    private static final String APP = "111122223333.dkr.ecr.eu-west-1.amazonaws.com/modocument-staging/app@" + DIGEST_A;
    private static final String FRONTEND =
            "111122223333.dkr.ecr.eu-west-1.amazonaws.com/modocument-staging/frontend@" + DIGEST_B;
    private static final String NEW_ARN =
            "arn:aws:ecs:eu-west-1:111122223333:task-definition/modocument-staging:8";
    private static final String OLD_ARN =
            "arn:aws:ecs:eu-west-1:111122223333:task-definition/modocument-staging:7";

    private static final String DESCRIBE = """
            {"taskDefinition": {
              "taskDefinitionArn": "arn:aws:ecs:eu-west-1:111122223333:task-definition/modocument-staging:7",
              "family": "modocument-staging", "revision": 7, "status": "ACTIVE",
              "requiresAttributes": [{"name": "x"}], "compatibilities": ["EC2"],
              "registeredAt": "2026-10-01T00:00:00Z", "registeredBy": "arn:aws:iam::1:role/ci",
              "executionRoleArn": "arn:aws:iam::111122223333:role/exec",
              "containerDefinitions": [
                {"name": "frontend", "image": "repo/frontend:1.0.0", "user": "101"},
                {"name": "app", "image": "repo/app:1.0.0",
                 "environment": [{"name": "MODOCUMENT_DEPLOY_ENVIRONMENT", "value": "staging"}]},
                {"name": "sidecar", "image": "repo/sidecar:1"}
              ]}}
            """;

    @TempDir
    Path work;

    @BeforeAll
    static void requireTools() {
        assumeTrue(run(REPO, Map.of(), "bash", "-c", "command -v jq && command -v curl").exitCode() == 0,
                "bash, jq or curl not available");
    }

    // ------------------------------------------------------- task definition

    @Test
    void theNextRevisionPinsBothImagesByDigestAndKeepsTheTemplate() throws Exception {
        Path describe = write("describe.json", DESCRIBE);
        Result result = run(REPO, Map.of(), "bash", SCRIPTS.resolve("render-task-definition.sh").toString(),
                describe.toString(), APP, FRONTEND);
        assertThat(result.exitCode()).as(result.output()).isZero();

        JsonNode next = MAPPER.readTree(result.output());
        assertThat(next.has("taskDefinitionArn")).isFalse();
        assertThat(next.has("revision")).isFalse();
        assertThat(next.has("status")).isFalse();
        assertThat(next.has("requiresAttributes")).isFalse();
        assertThat(next.has("compatibilities")).isFalse();
        assertThat(next.has("registeredAt")).isFalse();
        assertThat(next.has("registeredBy")).isFalse();
        assertThat(next.get("family").asText()).isEqualTo("modocument-staging");
        assertThat(next.get("executionRoleArn").asText()).endsWith("role/exec");
        Map<String, JsonNode> containers = new HashMap<>();
        next.get("containerDefinitions").forEach(c -> containers.put(c.get("name").asText(), c));
        assertThat(containers.get("app").get("image").asText()).isEqualTo(APP);
        assertThat(containers.get("frontend").get("image").asText()).isEqualTo(FRONTEND);
        assertThat(containers.get("frontend").get("user").asText()).isEqualTo("101");
        assertThat(containers.get("sidecar").get("image").asText()).isEqualTo("repo/sidecar:1");
        assertThat(containers.get("app").get("environment").get(0).get("value").asText()).isEqualTo("staging");
    }

    @Test
    void onlyDigestPinnedImagesAndACompleteTemplateAreAccepted() throws Exception {
        Path describe = write("describe.json", DESCRIBE);
        String render = SCRIPTS.resolve("render-task-definition.sh").toString();

        Result byTag = run(REPO, Map.of(), "bash", render, describe.toString(),
                "111122223333.dkr.ecr.eu-west-1.amazonaws.com/modocument-staging/app:1.2.0", FRONTEND);
        assertThat(byTag.exitCode()).isEqualTo(1);
        assertThat(byTag.output()).contains("is not <registry>/<repository>@sha256:<digest>");

        Path noFrontend = write("partial.json", DESCRIBE.replace("\"name\": \"frontend\"", "\"name\": \"web\""));
        Result partial = run(REPO, Map.of(), "bash", render, noFrontend.toString(), APP, FRONTEND);
        assertThat(partial.exitCode()).isNotZero();
        assertThat(partial.output()).contains("must have the containers");
    }

    // ------------------------------------------------------------ ECS rollout

    @Test
    void aDeploymentThatCompletesPrintsTheNewRevision() throws Exception {
        Path aws = fakeAws(DESCRIBE,
                services(NEW_ARN, "IN_PROGRESS", ""),
                services(NEW_ARN, "COMPLETED", "ECS deployment completed."));
        Result result = deploy(aws);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output().strip()).endsWith(NEW_ARN);
        JsonNode registered = MAPPER.readTree(Files.readString(work.resolve("aws/registered.json")));
        assertThat(registered.get("containerDefinitions").toString()).contains(APP).contains(FRONTEND);
        assertThat(Files.readString(work.resolve("aws/calls.log")))
                .contains("ecs update-service --cluster modocument-staging --service modocument-staging "
                        + "--task-definition " + NEW_ARN);
    }

    @Test
    void aCircuitBreakerRollbackOrAFailedRolloutFailsTheDeploy() throws Exception {
        Result rolledBack = deploy(fakeAws(DESCRIBE,
                services(NEW_ARN, "IN_PROGRESS", ""),
                services(OLD_ARN, "IN_PROGRESS", "rolling back to " + OLD_ARN)));
        assertThat(rolledBack.exitCode()).isEqualTo(1);
        assertThat(rolledBack.output()).contains("circuit breaker rolled back");

        Result failed = deploy(fakeAws(DESCRIBE,
                services(NEW_ARN, "FAILED", "tasks failed to start")));
        assertThat(failed.exitCode()).isEqualTo(1);
        assertThat(failed.output()).contains("rollout FAILED: tasks failed to start");
    }

    // -------------------------------------------------------------- image push

    @Test
    void imagesArePushedUnchangedUnderImmutableTags() throws Exception {
        Path archive = ociArchive(DIGEST_A);
        Path bin = fakeRegistry(DIGEST_A);
        String target = "111122223333.dkr.ecr.eu-west-1.amazonaws.com/modocument-staging/app";

        Result pushed = push(bin, archive, target, DIGEST_A, "1.2.0", "1.2.0-abc1234");
        assertThat(pushed.exitCode()).as(pushed.output()).isZero();
        assertThat(pushed.output()).contains(target + ":1.2.0 = " + DIGEST_A)
                .contains(target + ":1.2.0-abc1234 = " + DIGEST_A);
        assertThat(Files.readString(work.resolve("registry/skopeo.log")))
                .contains("--preserve-digests oci-archive:" + archive + " docker://" + target + ":1.2.0");

        // A re-run finds the same digest and skips; another digest is refused.
        Result rerun = push(bin, archive, target, DIGEST_A, "1.2.0");
        assertThat(rerun.exitCode()).isZero();
        assertThat(rerun.output()).contains("already holds " + DIGEST_A + " (re-run); skipped");
        Files.writeString(work.resolve("registry/tags/1.3.0"), DIGEST_B);
        Result clash = push(bin, archive, target, DIGEST_A, "1.3.0");
        assertThat(clash.exitCode()).isEqualTo(1);
        assertThat(clash.output()).contains("tags are immutable");
    }

    @Test
    void anArchiveThatIsNotTheBuiltImageIsNeverPushed() throws Exception {
        Path archive = ociArchive(DIGEST_B);
        Path bin = fakeRegistry(DIGEST_B);
        Result result = push(bin, archive, "111122223333.dkr.ecr.eu-west-1.amazonaws.com/x/app", DIGEST_A, "1.2.0");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("refusing to push");
        assertThat(work.resolve("registry/skopeo.log")).doesNotExist();
    }

    // -------------------------------------------------------------- smoke test

    @Test
    void theSmokeTestWantsTheReleasedVersionSeveralTimesInARow() throws Exception {
        AtomicReference<String> version = new AtomicReference<>("1.2.0");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/probe/synthetic", exchange -> {
            byte[] body = ("{\"status\":\"UP\",\"version\":\"" + version.get()
                    + "\",\"db\":true,\"checkedAt\":\"now\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> fast = Map.of("SMOKE_ALLOW_HTTP", "1", "SMOKE_INTERVAL_SECONDS", "0",
                    "SMOKE_ATTEMPTS", "4");
            String smoke = SCRIPTS.resolve("smoke-test.sh").toString();

            Result ok = run(REPO, fast, "bash", smoke, base, "1.2.0");
            assertThat(ok.exitCode()).as(ok.output()).isZero();
            assertThat(ok.output()).contains("OK").contains("(3/3)");

            Result stale = run(REPO, fast, "bash", smoke, base, "1.3.0");
            assertThat(stale.exitCode()).isEqualTo(1);
            assertThat(stale.output()).contains("never reported version 1.3.0");

            Result plainHttp = run(REPO, Map.of(), "bash", smoke, base, "1.2.0");
            assertThat(plainHttp.exitCode()).isEqualTo(2);
            assertThat(plainHttp.output()).contains("must be https://");
        } finally {
            server.stop(0);
        }
    }

    // ----------------------------------------------------------- release notes

    @Test
    void theReleaseNotesAreTheChangelogSectionOfTheVersion() throws Exception {
        Path changelog = write("CHANGELOG.md", """
                # Changelog

                ## [Unreleased]

                ### Added
                - Not yet.

                ## [1.1.0] - 2026-11-01

                ### Added
                - Announcements.

                ### Fixed
                - A bug.

                ## [1.0.0] - 2026-10-10

                ### Added
                - Everything.

                [1.1.0]: https://example.invalid/compare/v1.0.0...v1.1.0
                """);
        String script = SCRIPTS.resolve("changelog-section.sh").toString();

        Result notes = run(REPO, Map.of(), "bash", script, "1.1.0", changelog.toString());
        assertThat(notes.exitCode()).as(notes.output()).isZero();
        assertThat(notes.output()).isEqualTo("### Added\n- Announcements.\n\n### Fixed\n- A bug.\n");

        Result last = run(REPO, Map.of(), "bash", script, "1.0.0", changelog.toString());
        assertThat(last.output()).isEqualTo("### Added\n- Everything.\n");

        assertThat(run(REPO, Map.of(), "bash", script, "2.0.0", changelog.toString()).exitCode()).isEqualTo(1);
        assertThat(run(REPO, Map.of(), "bash", script, "v1.1.0", changelog.toString()).exitCode()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private Result deploy(Path awsDir) {
        return run(REPO, Map.of("PATH", awsDir + ":" + System.getenv("PATH"),
                        "FAKE_AWS_DIR", awsDir.toString(), "DEPLOY_POLL_SECONDS", "0"),
                "bash", SCRIPTS.resolve("ecs-deploy.sh").toString(),
                "modocument-staging", "modocument-staging", "modocument-staging", APP, FRONTEND);
    }

    private Result push(Path bin, Path archive, String target, String expected, String... tags) {
        String[] command = new String[5 + tags.length];
        command[0] = "bash";
        command[1] = SCRIPTS.resolve("push-oci.sh").toString();
        command[2] = archive.toString();
        command[3] = target;
        command[4] = expected;
        System.arraycopy(tags, 0, command, 5, tags.length);
        return run(REPO, Map.of("PATH", bin + ":" + System.getenv("PATH"),
                "FAKE_REGISTRY_DIR", work.resolve("registry").toString()), command);
    }

    private static String services(String primaryTaskDefinition, String rolloutState, String reason) {
        return "{\"services\":[{\"deployments\":[{\"status\":\"PRIMARY\",\"taskDefinition\":\""
                + primaryTaskDefinition + "\",\"rolloutState\":\"" + rolloutState
                + "\",\"rolloutStateReason\":\"" + reason + "\",\"runningCount\":2,\"desiredCount\":2},"
                + "{\"status\":\"ACTIVE\",\"taskDefinition\":\"" + OLD_ARN + "\"}]}]}";
    }

    /** An `aws` stand-in: the task definition, the registration, then the given service states in turn. */
    private Path fakeAws(String describe, String... serviceStates) throws IOException {
        Path dir = Files.createDirectories(work.resolve("aws"));
        Files.writeString(dir.resolve("describe.json"), describe);
        for (int i = 0; i < serviceStates.length; i++) {
            Files.writeString(dir.resolve("services-" + (i + 1) + ".json"), serviceStates[i]);
        }
        Files.writeString(dir.resolve("services-last.json"), serviceStates[serviceStates.length - 1]);
        Files.deleteIfExists(dir.resolve("polls"));
        executable(dir.resolve("aws"), """
                #!/usr/bin/env bash
                set -euo pipefail
                echo "$*" >> "$FAKE_AWS_DIR/calls.log"
                case "$1 $2" in
                  "ecs describe-task-definition") cat "$FAKE_AWS_DIR/describe.json" ;;
                  "ecs register-task-definition")
                    for arg in "$@"; do
                      case "$arg" in file://*) cp "${arg#file://}" "$FAKE_AWS_DIR/registered.json" ;; esac
                    done
                    echo "%s" ;;
                  "ecs update-service") echo '{}' ;;
                  "ecs describe-services")
                    n=$(( $(cat "$FAKE_AWS_DIR/polls" 2>/dev/null || echo 0) + 1 ))
                    echo "$n" > "$FAKE_AWS_DIR/polls"
                    cat "$FAKE_AWS_DIR/services-$n.json" 2>/dev/null || cat "$FAKE_AWS_DIR/services-last.json" ;;
                  *) echo "unexpected aws call: $*" >&2; exit 9 ;;
                esac
                """.formatted(NEW_ARN));
        return dir;
    }

    /** `aws ecr describe-images` and `skopeo copy` stand-ins sharing a tag → digest store. */
    private Path fakeRegistry(String archiveDigest) throws IOException {
        Path registry = Files.createDirectories(work.resolve("registry/tags"));
        Path bin = Files.createDirectories(work.resolve("registry-bin"));
        executable(bin.resolve("aws"), """
                #!/usr/bin/env bash
                tag=""
                for arg in "$@"; do case "$arg" in imageTag=*) tag="${arg#imageTag=}" ;; esac; done
                if [ -f "$FAKE_REGISTRY_DIR/tags/$tag" ]; then cat "$FAKE_REGISTRY_DIR/tags/$tag"; exit 0; fi
                echo "An error occurred (ImageNotFoundException)" >&2
                exit 254
                """);
        executable(bin.resolve("skopeo"), """
                #!/usr/bin/env bash
                echo "$*" >> "$FAKE_REGISTRY_DIR/skopeo.log"
                dest="${!#}"
                printf '%%s' "%s" > "$FAKE_REGISTRY_DIR/tags/${dest##*:}"
                """.formatted(archiveDigest));
        return bin;
    }

    /** A tar whose index.json names one manifest with the given digest (enough for push-oci.sh). */
    private Path ociArchive(String digest) throws IOException {
        Path layout = Files.createDirectories(work.resolve("layout-" + digest.substring(7, 12)));
        Files.writeString(layout.resolve("index.json"), "{\"schemaVersion\":2,\"manifests\":[{\"mediaType\":"
                + "\"application/vnd.oci.image.manifest.v1+json\",\"digest\":\"" + digest + "\",\"size\":1}]}");
        Files.writeString(layout.resolve("oci-layout"), "{\"imageLayoutVersion\":\"1.0.0\"}");
        Path archive = work.resolve("image-" + digest.substring(7, 12) + ".oci.tar");
        Result tar = run(layout, Map.of(), "tar", "-cf", archive.toString(), "index.json", "oci-layout");
        assertThat(tar.exitCode()).as(tar.output()).isZero();
        return archive;
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(work.resolve(name), content);
    }

    private static void executable(Path file, String content) throws IOException {
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
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
