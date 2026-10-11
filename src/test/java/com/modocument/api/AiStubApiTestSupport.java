package com.modocument.api;

import com.modocument.testsupport.SharedTestStorage;
import com.modocument.testsupport.StubAiServer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * One Spring context for the API suites that drive the AI path against a
 * real loopback model ({@link StubAiServer}): Spring AI's retry policy, the
 * HTTP read timeout (2 s here, to keep the hang case short), the circuit
 * breaker and the failure classifier all run for real.
 *
 * <p>The stub and the storage root live for the whole test JVM, and the
 * dynamic properties are declared on THIS class only, so every subclass
 * resolves to the same context-cache key (it used to be one context per
 * class with identical settings). Subclasses set the stub's mode and reset
 * its call counter and the circuit breaker in their own {@code @BeforeEach};
 * test classes never run concurrently in this build.
 */
@SpringBootTest(properties = "modocument.ai.http.read-timeout=2s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AiStubApiTestSupport {

    /** The model endpoint of the shared context; never closed (daemon threads). */
    protected static final StubAiServer AI = new StubAiServer();

    /** {@code modocument.storage.root} of the shared context. */
    protected static final Path STORAGE_ROOT = SharedTestStorage.directory("ai-stub-api");

    @DynamicPropertySource
    static void aiStubProperties(DynamicPropertyRegistry registry) {
        registry.add("modocument.storage.root", STORAGE_ROOT::toString);
        registry.add("spring.ai.openai.base-url", AI::baseUrl);
    }
}
