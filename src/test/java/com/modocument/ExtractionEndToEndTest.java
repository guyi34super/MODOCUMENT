package com.modocument;

import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.ProcessedRecordStore;
import com.modocument.application.usecase.BridgeToTemplateUseCase;
import com.modocument.application.usecase.CreateTemplateWithFieldsUseCase;
import com.modocument.application.usecase.ExtractionJobUseCase;
import com.modocument.application.usecase.ExtractionSourceUseCase;
import com.modocument.application.dto.CreateTemplateFieldDto;
import com.modocument.application.dto.CreateTemplateRequest;
import com.modocument.application.dto.SaveExtractionSourceRequest;
import com.modocument.domain.document.ExtractionJob;
import com.modocument.domain.document.ExtractionSource;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.ExtractionJobStatus;
import com.modocument.domain.model.SourceType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Full-stack extraction test against a local HTTP server: robots.txt is
 * fetched and honored, the crawl follows an in-domain link (one page is
 * robots-blocked), records are normalized and deduplicated, the real worker
 * pool drives the job to completion, and the bridge lands the rows in the
 * existing document pipeline. No external network, no Docker.
 */
@SpringBootTest(properties = {
        "extraction.worker.enabled=true",
        "extraction.worker.poll-millis=50",
        "extraction.web.default-delay-millis=0"
})
@ActiveProfiles("test")
class ExtractionEndToEndTest {

    private static HttpServer server;
    private static String baseUrl;

    @Autowired
    private ExtractionSourceUseCase sources;
    @Autowired
    private ExtractionJobUseCase jobs;
    @Autowired
    private BridgeToTemplateUseCase bridge;
    @Autowired
    private CreateTemplateWithFieldsUseCase createTemplate;
    @Autowired
    private ProcessedRecordStore processedRecords;
    @Autowired
    private ImportHistoryRepository importHistory;
    @Autowired
    private ImportDetailsRepository importDetails;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/robots.txt", "text/plain", """
                User-agent: *
                Disallow: /private/
                """);
        serve("/index.html", "text/html", """
                <html><body>
                <table>
                <tr><th>EmployeeNumber</th><th>FirstName</th></tr>
                <tr><td>100</td><td>Ada</td></tr>
                <tr><td>101</td><td>Alan</td></tr>
                </table>
                <a href="/page2.html">more</a>
                <a href="/private/secret.html">private</a>
                </body></html>
                """);
        serve("/page2.html", "text/html", """
                <html><body>
                <table>
                <tr><th>EmployeeNumber</th><th>FirstName</th></tr>
                <tr><td>102</td><td>Grace</td></tr>
                </table>
                </body></html>
                """);
        serve("/private/secret.html", "text/html", "<html><body>must never be fetched</body></html>");
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void serve(String path, String contentType, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void authenticate() {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").subject("1")
                .claim("bank_id", 1).claim("roles", java.util.List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void crawlRespectsRobotsExtractsRecordsAndBridgesIntoDocumentPipeline() {
        SaveExtractionSourceRequest request = new SaveExtractionSourceRequest();
        request.setName("Local test site");
        request.setType(SourceType.WEBSITE);
        request.setTermsAcknowledged(true);
        WebSourceConfig web = new WebSourceConfig();
        web.setSeedUrls(List.of(baseUrl + "/index.html"));
        web.setAllowedDomains(List.of("127.0.0.1"));
        web.setMaxDepth(1);
        web.setMaxPages(10);
        request.setWeb(web);
        ExtractionSource source = sources.create(request);

        ExtractionJob job = jobs.create(source.getId(), null, "employee numbers and names",
                false, null);
        String jobId = jobs.start(job.getId()).getId();

        // jobs.get is owner-scoped and reads the caller from the SecurityContext,
        // which is a thread-local set on this test thread; poll on the same thread
        // so the authenticated context is in scope (Awaitility polls off-thread by
        // default). The worker pool that drives the job to COMPLETED runs on its own
        // threads but uses the unscoped store, so it needs no context.
        await().pollInSameThread().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jobs.get(jobId).getStatus()).isEqualTo(ExtractionJobStatus.COMPLETED));

        // Three table rows from the two allowed pages; the /private/ page was
        // robots-blocked, so its content never became a record.
        assertThat(processedRecords.countByJob(jobId)).isEqualTo(3);
        assertThat(processedRecords.findBatch(jobId, null, 10))
                .allSatisfy(record -> assertThat(record.getFields().values())
                        .noneMatch(value -> value.contains("must never be fetched")));

        // EmployeeNumber/FirstName match the template's fields exactly, so the
        // bridge needs no LLM.
        int templateId = createTemplate.handle(new CreateTemplateRequest("Employee Extract", "E2E test template",
                List.of(new CreateTemplateFieldDto("EmployeeNumber", "Number", List.of()),
                        new CreateTemplateFieldDto("FirstName", "Text", List.of()))))
                .templateId();
        BridgeToTemplateUseCase.BridgeResult result = bridge.bridge(jobId, templateId, null, false);
        assertThat(result.rowsBridged()).isEqualTo(3);
        assertThat(result.ingestionJobId()).isPositive();
        assertThat(result.mapping()).containsEntry("EmployeeNumber", "EmployeeNumber");

        // The document pipeline's audit rows exist (Transforming/Mapping/Validating).
        assertThat(importHistory.getById(result.importHistoryId())).isPresent();
        assertThat(importDetails.list().stream()
                .filter(d -> result.importHistoryId().equals(d.getImportHistoryId()))
                .count()).isEqualTo(3);
    }
}
