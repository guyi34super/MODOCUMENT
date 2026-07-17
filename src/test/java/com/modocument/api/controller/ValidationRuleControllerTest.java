package com.modocument.api.controller;

import com.modocument.application.dto.UpsertValidationRuleRequest;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidationRuleControllerTest {

    private final ValidationRuleRepository repo = mock(ValidationRuleRepository.class);
    private final ValidationRuleController controller = new ValidationRuleController(repo);

    @Test
    void upsertSavesValidRegexRule() {
        when(repo.save(any(ValidationRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.upsert(new UpsertValidationRuleRequest(
                1, "PostalCode", "REGEX", Map.of("Pattern", "^[A-Z]\\d[A-Z] ?\\d[A-Z]\\d$")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ValidationRule) response.getBody()).getRuleType()).isEqualTo(RuleType.REGEX);
    }

    @Test
    void upsertRejectsInvalidRegex() {
        ResponseEntity<?> response = controller.upsert(new UpsertValidationRuleRequest(
                1, "PostalCode", "REGEX", Map.of("Pattern", "([A-Z")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repo, never()).save(any());
    }

    @Test
    void upsertRejectsUnknownRuleType() {
        ResponseEntity<?> response = controller.upsert(new UpsertValidationRuleRequest(
                1, "PostalCode", "NOPE", Map.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteReturns204WhenRemovedAnd404WhenMissing() {
        when(repo.delete(1, "PostalCode", RuleType.REGEX)).thenReturn(true, false);

        assertThat(controller.delete(1, "PostalCode", "REGEX").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.delete(1, "PostalCode", "REGEX").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
