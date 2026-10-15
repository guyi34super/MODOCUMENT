package com.modocument.doccreation.api;

import com.modocument.application.port.AuditEventSpec;
import com.modocument.doccreation.dto.GenerationResultDto;
import com.modocument.doccreation.usecase.DocInstanceUseCase;
import com.modocument.domain.model.AuditOutcome;
import com.modocument.testsupport.RecordingAuditRecorder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ws28: generating a document records DOC_GENERATED (with the exactness
 * result and the added pages, ws31) and downloading it DOC_DOWNLOADED, once
 * per call, for that instance; a refused call records neither.
 */
class DocInstanceControllerAuditTest {

    private final DocInstanceUseCase useCase = mock(DocInstanceUseCase.class);
    private final RecordingAuditRecorder audit = new RecordingAuditRecorder();
    private final DocInstanceController controller = new DocInstanceController(useCase, audit);

    @Test
    void generateIsAuditedOnceWithTheExactnessResult() {
        when(useCase.generate(12)).thenReturn(new GenerationResultDto(true, 1, List.of("notes"), true,
                LocalDateTime.now()));

        controller.generate(12);

        assertThat(audit.recorded()).singleElement().satisfies(spec -> {
            assertThat(spec.action()).isEqualTo("DOC_GENERATED");
            assertThat(spec.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(spec.entityType()).isEqualTo("DocInstance");
            assertThat(spec.entityId()).isEqualTo("12");
            assertThat(((Map<?, ?>) spec.newValues()).get("exact")).isEqualTo(true);
            assertThat(((Map<?, ?>) spec.newValues()).get("addedPages")).isEqualTo(1);
        });
    }

    @Test
    void downloadIsAuditedOnceWithItsFormat() {
        when(useCase.download(12)).thenReturn(new DocInstanceUseCase.PdfDownload(
                "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "Survey.pdf"));

        controller.download(12, null);

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
