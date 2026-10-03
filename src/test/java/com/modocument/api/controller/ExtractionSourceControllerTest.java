package com.modocument.api.controller;

import com.modocument.testsupport.FixedUser;
import com.modocument.application.dto.ExtractionSourceDto;
import com.modocument.application.dto.SaveExtractionSourceRequest;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.usecase.ExtractionSourceUseCase;
import com.modocument.domain.document.WebSourceConfig;
import com.modocument.domain.model.SourceType;
import com.modocument.infrastructure.extraction.memory.InMemoryExtractionSourceStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExtractionSourceControllerTest {

    private final ExtractionSourceController controller = new ExtractionSourceController(
            new ExtractionSourceUseCase(new InMemoryExtractionSourceStore(),
                    mock(TemplateRepository.class), FixedUser.provider(1)));

    private SaveExtractionSourceRequest websiteRequest(boolean termsAcknowledged) {
        SaveExtractionSourceRequest request = new SaveExtractionSourceRequest();
        request.setName("Docs site");
        request.setType(SourceType.WEBSITE);
        request.setTermsAcknowledged(termsAcknowledged);
        WebSourceConfig web = new WebSourceConfig();
        web.setSeedUrls(List.of("https://docs.example.com/start"));
        web.setAllowedDomains(List.of("example.com"));
        request.setWeb(web);
        return request;
    }

    @Test
    void createReturnsCreatedSource() {
        ResponseEntity<?> response = controller.create(websiteRequest(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ExtractionSourceDto dto = (ExtractionSourceDto) response.getBody();
        assertThat(dto.id()).isNotBlank();
        assertThat(dto.type()).isEqualTo(SourceType.WEBSITE);
    }

    @Test
    void creationWithoutTermsAcknowledgmentIsRejected() {
        // The controller's @ExceptionHandler maps this to 400.
        assertThatThrownBy(() -> controller.create(websiteRequest(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("termsAcknowledged");
        assertThat(controller.badRequest(new IllegalArgumentException("x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void seedUrlOutsideAllowedDomainsIsRejected() {
        SaveExtractionSourceRequest request = websiteRequest(true);
        request.getWeb().setSeedUrls(List.of("https://other.org/page"));

        assertThatThrownBy(() -> controller.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedDomains");
    }

    @Test
    void crudRoundTripWorks() {
        ExtractionSourceDto created =
                (ExtractionSourceDto) controller.create(websiteRequest(true)).getBody();

        assertThat(((List<?>) controller.list().getBody())).hasSize(1);
        assertThat(controller.get(created.id()).getStatusCode()).isEqualTo(HttpStatus.OK);

        SaveExtractionSourceRequest update = websiteRequest(true);
        update.setName("Renamed");
        ExtractionSourceDto updated =
                (ExtractionSourceDto) controller.update(created.id(), update).getBody();
        assertThat(updated.name()).isEqualTo("Renamed");

        assertThat(controller.delete(created.id()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(((List<?>) controller.list().getBody())).isEmpty();
    }
}
