package com.modocument.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate C9 guard — the AI gateway is the ONLY component allowed to touch the
 * Spring AI chat types. A new direct caller would bypass the one-bank check,
 * the tokenization leak scan, the no-content logging rule and the output
 * scanner, so this pragmatic import scan fails the build on any reference to
 * ChatClient/ChatModel (or the OpenAI chat options/model classes) outside
 * {@code com.modocument.infrastructure.ai}.
 */
class NoDirectChatModelAccessGuardTest {

    /** References that mean "this class can talk to the model directly". */
    private static final Pattern CHAT_TYPES = Pattern.compile(
            "org\\.springframework\\.ai\\.chat\\.client\\."
                    + "|org\\.springframework\\.ai\\.chat\\.model\\."
                    + "|org\\.springframework\\.ai\\.openai\\.OpenAiChat");

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final String ALLOWED_PACKAGE_DIR =
            Path.of("com", "modocument", "infrastructure", "ai").toString();

    @Test
    void onlyTheAiGatewayPackageReferencesSpringAiChatTypes() throws IOException {
        assertThat(MAIN_SOURCES).isDirectory(); // surefire runs in the project basedir

        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !MAIN_SOURCES.relativize(path).toString()
                            .startsWith(ALLOWED_PACKAGE_DIR))
                    .filter(NoDirectChatModelAccessGuardTest::referencesChatTypes)
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("classes outside infrastructure/ai referencing Spring AI chat "
                            + "types — route the call through AiGateway (AiFilesService) "
                            + "with a tokenized PromptEnvelope instead")
                    .isEmpty();
        }
    }

    private static boolean referencesChatTypes(Path file) {
        try {
            return CHAT_TYPES.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
