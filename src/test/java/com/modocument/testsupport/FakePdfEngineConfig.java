package com.modocument.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wires the {@link FakePdfTemplateEngine} as THE PdfTemplateEngine of a test
 * context (@Primary, so it wins over the real DefaultPdfTemplateEngine once
 * that is on the classpath). Imported by the shared API test base
 * (IsolatedStorageApiTestSupport) and the PostgreSQL Spring IT base, so the
 * doc-creation suites share one context each.
 */
@TestConfiguration
public class FakePdfEngineConfig {

    @Bean
    @Primary
    public FakePdfTemplateEngine fakePdfTemplateEngine() {
        return new FakePdfTemplateEngine();
    }
}
