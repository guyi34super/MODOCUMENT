package com.modocument.doccreation.api;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.testsupport.RecordingAuditRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ws28: generating a document records DOC_GENERATED and downloading it
 * DOC_DOWNLOADED, once per call, for that instance (the actor is enriched
 * by the recorder from the caller's token); a refused call records neither.
 */
class DocInstanceControllerAuditTest {

    @TempDir
    Path tempDir;

    private final DocInstanceUseCase useCase = mock(DocInstanceUseCase.class);
    private final RecordingAuditRecorder audit = new RecordingAuditRecorder();
    private final DocInstanceController controller = new DocInstanceController(useCase, audit);

    @Test
    void generateIsAuditedOnce() {
        when(useCase.generate(12)).thenReturn(new GenerationResultDto("doc12.docx", null, false,
                null, LocalDateTime.now(), "ENGINE"));

        controller.generate(12);

        assertThat(audit.recorded()).singleElement().satisfies(spec -> {
            assertThat(spec.action()).isEqualTo("DOC_GENERATED");
            assertThat(spec.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(spec.entityType()).isEqualTo("DocInstance");
            assertThat(spec.entityId()).isEqualTo("12");
        });
    }

    @Test
    void downloadIsAuditedOnceWithItsFormat() throws Exception {
        Path pdf = Files.writeString(tempDir.resolve("doc12.pdf"), "%PDF-1.7");
        when(useCase.download(12, "pdf")).thenReturn(
                new DocInstanceUseCase.DownloadFile(pdf, "Survey.pdf"));

        controller.download(12, "pdf");

        AuditEventSpec spec = audit.of("DOC_DOWNLOADED").get(0);
        assertThat(audit.recorded()).hasSize(1);
        assertThat(spec.entityType()).isEqualTo("DocInstance");
        assertThat(spec.entityId()).isEqualTo("12");
        assertThat(spec.newValues()).isEqualTo(Map.of("format", "pdf", "bytes", 8));
    }

    @Test
    void aRefusedGenerationRecordsNothing() {
        when(useCase.generate(13)).thenThrow(new NoSuchElementException("Document 13 not found."));

        assertThatThrownBy(() -> controller.generate(13)).isInstanceOf(NoSuchElementException.class);
        assertThat(audit.recorded()).isEmpty();
    }
}
