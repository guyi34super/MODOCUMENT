package com.modocument.infrastructure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.read.ListAppender;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.IngestionParseStep;
import com.modocument.application.port.ImportDetailsRepository;
import com.modocument.application.port.ImportHistoryRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.privacy.PiiPatterns;
import com.modocument.application.usecase.AutoMapUseCase;
import com.modocument.application.usecase.IngestionJobUseCase;
import com.modocument.application.usecase.ValidateRowsUseCase;
import com.modocument.domain.entity.IngestionJob;
import com.modocument.domain.model.JobStatus;
import com.modocument.domain.model.JobStep;
import com.modocument.testsupport.FixedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate C3 evidence: seeded canary PII/credential values are pushed through
 * realistic paths — a failed login carrying the canary password, server-side
 * error logging of exceptions that embed NIC/account/email (the
 * ApiExceptionHandler pattern: generic response, raw text to the log), an
 * ingestion-job failure whose message lands in the persisted ErrorMessage
 * column — and then EVERY log event the root logger saw is rendered through
 * the production console layout ({@code %mask}/{@code %maskEx} from
 * logback-spring.xml) and the real captured console output is searched. No
 * canary may appear anywhere; the redaction marker must.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LogRedactionCanaryTest {

    private static final String CANARY_NIC = "A1234567890123";
    private static final String CANARY_ACCOUNT = "12345678901234";
    private static final String CANARY_EMAIL = "canary@example.test";
    private static final String CANARY_PASSWORD = "CanaryPass123!x";
    private static final String CANARY_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjYW5hcnkifQ.c2lnbmF0dXJl";
    private static final List<String> CANARIES = List.of(
            CANARY_NIC, CANARY_ACCOUNT, CANARY_EMAIL, CANARY_PASSWORD, CANARY_JWT);

    private static final String REQUEST_ID = "0199c2f4-7d3a-7b5c-8a3e-4f2b1c9d0e7a";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IngestionJobRepository jobRepository;

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void captureRootLogger() {
        captured.start();
        rootLogger().addAppender(captured);
    }

    @AfterEach
    void detach() {
        rootLogger().detachAppender(captured);
        MDC.clear();
    }

    @Test
    void seededCanariesNeverReachAnyLogOutput(CapturedOutput output) throws Exception {
        // 1. A failed login attempt carrying the canary password and email.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + CANARY_EMAIL + "\","
                                + "\"password\":\"" + CANARY_PASSWORD + "\"}"))
                .andExpect(status().is4xxClientError());

        // After the request (RequestIdFilter clears the MDC on exit), pin
        // the identity fields the E9 pattern prints, to prove they survive.
        MDC.put("requestId", REQUEST_ID);
        MDC.put("userId", "4242");
        MDC.put("bankId", "1");

        // 2. Server-side error logging as ApiExceptionHandler does it: the
        // response stays generic but the raw exception text goes to the log
        // — message AND stack trace must come out scrubbed.
        org.slf4j.Logger serverLog = LoggerFactory.getLogger(
                com.modocument.api.controller.ApiExceptionHandler.class);
        serverLog.error("Unhandled failure", new IllegalStateException(
                "insert failed: VALUES ('" + CANARY_NIC + "', '" + CANARY_ACCOUNT
                        + "', '" + CANARY_EMAIL + "')"));
        serverLog.warn("login rejected password={} header Authorization: Bearer {} "
                        + "cookie modoc_refresh=AbC-123_xyz0987654321 jwt " + CANARY_JWT,
                CANARY_PASSWORD, CANARY_JWT);

        // 3. An ingestion-job failure whose exception message embeds the
        // canaries: the persisted ErrorMessage column must be scrubbed too.
        int jobId = runFailingIngestionJob();
        IngestionJob failed = jobRepository.getById(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage())
                .contains(PiiPatterns.MASK)
                .contains("Row 7 rejected"); // still tells ops what happened
        CANARIES.forEach(canary -> assertThat(failed.getErrorMessage()).doesNotContain(canary));

        // 4. Log utility survives: an ordinary INFO line is untouched.
        LoggerFactory.getLogger(LogRedactionCanaryTest.class)
                .info("Ingestion job 42 completed for bank 1");

        // ---- the C3 assertion: no canary in any event's formatted output ----
        Layout<ILoggingEvent> layout = consoleLayout();
        assertThat(captured.list).isNotEmpty();
        StringBuilder rendered = new StringBuilder();
        for (ILoggingEvent event : captured.list) {
            rendered.append(layout.doLayout(event));
        }
        String formatted = rendered.toString();
        CANARIES.forEach(canary -> assertThat(formatted).doesNotContain(canary));
        assertThat(formatted)
                .contains(PiiPatterns.MASK)
                .contains("c***@example.test") // domain kept, local part masked
                .contains(REQUEST_ID) // request ids are never redacted
                .contains("Ingestion job 42 completed for bank 1");

        // Belt and braces: the REAL console output (written through the
        // production encoder) is equally free of canaries.
        String console = output.getAll();
        CANARIES.forEach(canary -> assertThat(console).doesNotContain(canary));
        assertThat(console).contains(PiiPatterns.MASK);
    }

    /**
     * Runs the real {@link IngestionJobUseCase#resume} failure path against
     * the real repository: the parse stage throws with canary-laden text,
     * runStage records it, and the saved ErrorMessage must be scrubbed.
     */
    private int runFailingIngestionJob() {
        IngestionParseStep failingParse = mock(IngestionParseStep.class);
        when(failingParse.parse(any())).thenThrow(new IllegalStateException(
                "Row 7 rejected: NIC " + CANARY_NIC + ", account " + CANARY_ACCOUNT
                        + ", holder " + CANARY_EMAIL));
        IngestionJobUseCase useCase = new IngestionJobUseCase(
                jobRepository,
                mock(TemplateRepository.class),
                mock(TemplateFieldsRepository.class),
                mock(ImportHistoryRepository.class),
                mock(ImportDetailsRepository.class),
                failingParse,
                mock(AutoMapUseCase.class),
                mock(ValidateRowsUseCase.class),
                FixedUser.provider(4242));

        IngestionJob job = new IngestionJob();
        job.setOwnerUserId(4242);
        job.setBankId(1);
        job.setFileName("canary-upload.csv");
        job.setTemplateId(1);
        job.setCurrentStep(JobStep.UPLOADED);
        job.setStatus(JobStatus.PENDING);
        int jobId = jobRepository.save(job).getIngestionJobId();

        useCase.resume(jobId);
        return jobId;
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /** The CONSOLE appender's real layout — the production pattern with %mask/%maskEx. */
    private static Layout<ILoggingEvent> consoleLayout() {
        Appender<ILoggingEvent> console = rootLogger().getAppender("CONSOLE");
        assertThat(console).as("CONSOLE appender from logback-spring.xml").isNotNull();
        LayoutWrappingEncoder<ILoggingEvent> encoder = (LayoutWrappingEncoder<ILoggingEvent>)
                ((OutputStreamAppender<ILoggingEvent>) console).getEncoder();
        return encoder.getLayout();
    }
}
