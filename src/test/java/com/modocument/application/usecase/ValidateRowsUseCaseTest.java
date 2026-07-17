package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;
import com.modocument.application.dto.ValidationRequest;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import com.modocument.infrastructure.rule.DateFormatRule;
import com.modocument.infrastructure.rule.ExpiryDateRule;
import com.modocument.infrastructure.rule.RangeRule;
import com.modocument.infrastructure.rule.RegexRule;
import com.modocument.infrastructure.rule.RequiredRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidateRowsUseCaseTest {

    private final ValidationRuleRepository repo = mock(ValidationRuleRepository.class);
    private final ValidateRowsUseCase useCase = new ValidateRowsUseCase(repo, List.of(
            new RequiredRule(), new RegexRule(), new RangeRule(),
            new DateFormatRule(), new ExpiryDateRule()));

    @Test
    void emitsOneResultPerFieldPerRow() {
        when(repo.getRulesByTemplate(1)).thenReturn(List.of(
                new ValidationRule(1, "FirstName", RuleType.REQUIRED, "{}"),
                new ValidationRule(1, "Gender", RuleType.REGEX, "{\"Pattern\":\"^(M|F|U)$\"}")));

        List<RowValidationResult> results = useCase.handle(new ValidationRequest(1, List.of(
                Map.of("FirstName", "Ada", "Gender", "F"),
                Map.of("FirstName", "Grace", "Gender", "F"))));

        assertThat(results).hasSize(4);
        assertThat(results).allSatisfy(r -> assertThat(r.errorMessage()).isNull());
        assertThat(results.stream().map(RowValidationResult::row).distinct()).containsExactly(1, 2);
    }

    @Test
    void keepsOnlyFirstErrorPerField() {
        // Both rules fail for an empty value... except Regex skips blanks, so
        // use a value that fails Required's order: Required first, then Regex.
        when(repo.getRulesByTemplate(1)).thenReturn(List.of(
                new ValidationRule(1, "Gender", RuleType.REQUIRED, "{}"),
                new ValidationRule(1, "Gender", RuleType.REGEX, "{\"Pattern\":\"^(M|F|U)$\"}")));

        List<RowValidationResult> results =
                useCase.handle(new ValidationRequest(1, List.of(Map.of("Gender", ""))));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).errorMessage()).isEqualTo("Value is required.");
    }

    @Test
    void missingFieldIsValidatedAsEmptyString() {
        when(repo.getRulesByTemplate(1)).thenReturn(List.of(
                new ValidationRule(1, "FirstName", RuleType.REQUIRED, "{}")));

        List<RowValidationResult> results =
                useCase.handle(new ValidationRequest(1, List.of(Map.of("SomeOtherField", "x"))));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rawValue()).isEmpty();
        assertThat(results.get(0).errorMessage()).isEqualTo("Value is required.");
    }

    @Test
    void rulesWithoutValidatorAreSkipped() {
        when(repo.getRulesByTemplate(1)).thenReturn(List.of(
                new ValidationRule(1, "FirstName", RuleType.CUSTOM, "{}")));

        List<RowValidationResult> results =
                useCase.handle(new ValidationRequest(1, List.of(Map.of("FirstName", "Ada"))));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).errorMessage()).isNull();
    }

    @Test
    void rowNumbersAreOneBased() {
        when(repo.getRulesByTemplate(1)).thenReturn(List.of(
                new ValidationRule(1, "FirstName", RuleType.REQUIRED, "{}")));

        List<RowValidationResult> results = useCase.handle(new ValidationRequest(1, List.of(
                Map.of("FirstName", "Ada"),
                Map.of("FirstName", ""))));

        assertThat(results.get(0).row()).isEqualTo(1);
        assertThat(results.get(1).row()).isEqualTo(2);
        assertThat(results.get(1).errorMessage()).isEqualTo("Value is required.");
    }
}
