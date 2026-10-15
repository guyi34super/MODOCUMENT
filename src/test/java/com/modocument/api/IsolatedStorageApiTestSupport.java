package com.modocument.api;

import com.modocument.testsupport.FakePdfEngineConfig;
import com.modocument.testsupport.H2ExportTargets;
import com.modocument.testsupport.SharedTestStorage;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/**
 * One Spring context for the API suites that inspect files on disk
 * (uploads, export copies, generated documents): its own storage root,
 * shared by every subclass for the life of the test JVM, and the
 * doc-creation template AI off (no model call can happen), and the
 * FakePdfTemplateEngine as the PDF template engine.
 *
 * <p>Each suite used to declare its own storage root (and so got its own
 * context). They only ever look at files they created themselves, by name,
 * and none of them depends on the doc-creation AI being on, so one context
 * serves them all. The dynamic property is declared on THIS class only:
 * every subclass resolves to the same context-cache key.
 */
@SpringBootTest(properties = {
        "modocument.doccreation.section-ai-enabled=false",
        "modocument.doccreation.generate-ai-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
// ws28: structured DB-export targets land in in-memory H2 (MaskingGateC2Test);
// ws31: the doc-creation suites run on the steerable FakePdfTemplateEngine.
@Import({H2ExportTargets.class, FakePdfEngineConfig.class})
public abstract class IsolatedStorageApiTestSupport {

    /** {@code modocument.storage.root} of the shared context. */
    protected static final Path STORAGE_ROOT = SharedTestStorage.directory("isolated-storage-api");

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("modocument.storage.root", STORAGE_ROOT::toString);
    }
}
