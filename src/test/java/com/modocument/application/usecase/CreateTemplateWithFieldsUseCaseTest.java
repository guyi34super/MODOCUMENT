package com.modocument.application.usecase;

import com.modocument.application.dto.CreateTemplateFieldDto;
import com.modocument.application.dto.CreateTemplateRequest;
import com.modocument.application.dto.CreateTemplateResponse;
import com.modocument.application.dto.CreateTemplateRuleDto;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.validation.TemplateDefinitionException;
import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.RuleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTemplateWithFieldsUseCaseTest {

    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final TemplateFieldsRepository fields = mock(TemplateFieldsRepository.class);
    private final TemplateDataTypeRepository dataTypes = mock(TemplateDataTypeRepository.class);
    private final ValidationRuleRepository rules = mock(ValidationRuleRepository.class);

    private final CreateTemplateWithFieldsUseCase useCase =
            new CreateTemplateWithFieldsUseCase(templates, fields, dataTypes, rules);

    private void seedDataTypes() {
        when(dataTypes.list()).thenReturn(List.of(
                new TemplateDataType(1, "Text"),
                new TemplateDataType(2, "Number"),
                new TemplateDataType(3, "Date")));
    }

    @Test
    void createsTemplateFieldsAndRules() {
        seedDataTypes();

        CreateTemplateResponse response = useCase.handle(new CreateTemplateRequest(
                "Invoices", "Invoice template", List.of(
                new CreateTemplateFieldDto("InvoiceNo", "Text", List.of(
                        new CreateTemplateRuleDto("REGEX", Map.of("Pattern", "^INV-\\d{6}$")),
                        new CreateTemplateRuleDto("REQUIRED", Map.of()))),
                new CreateTemplateFieldDto("Amount", "2", null))));

        assertThat(response.name()).isEqualTo("Invoices");
        assertThat(response.fields()).hasSize(2);
        assertThat(response.fields().get(0).rules()).hasSize(2);
        assertThat(response.fields().get(1).dataType()).isEqualTo("Number");

        ArgumentCaptor<ValidationRule> savedRules = ArgumentCaptor.forClass(ValidationRule.class);
        verify(rules, org.mockito.Mockito.times(2)).save(savedRules.capture());
        assertThat(savedRules.getAllValues().get(0).getRuleType()).isEqualTo(RuleType.REGEX);
        assertThat(savedRules.getAllValues().get(0).getConfigJson()).contains("INV-");
    }

    @Test
    void invalidRegexIsRejectedWithDetailsAndNothingPersisted() {
        seedDataTypes();
        CreateTemplateRequest request = new CreateTemplateRequest("Bad", null, List.of(
                new CreateTemplateFieldDto("Code", "Text", List.of(
                        new CreateTemplateRuleDto("REGEX", Map.of("Pattern", "([A-Z"))))));

        assertThatThrownBy(() -> useCase.handle(request))
                .isInstanceOf(TemplateDefinitionException.class)
                .satisfies(e -> {
                    TemplateDefinitionException ex = (TemplateDefinitionException) e;
                    assertThat(ex.getErrors()).hasSize(1);
                    assertThat(ex.getErrors().get(0).fieldName()).isEqualTo("Code");
                    assertThat(ex.getErrors().get(0).message()).contains("Invalid regular expression");
                });

        verify(templates, never()).add(any());
        verify(rules, never()).save(any());
    }

    @Test
    void unknownRuleTypeAndDataTypeAreRejected() {
        seedDataTypes();
        CreateTemplateRequest request = new CreateTemplateRequest("Bad", null, List.of(
                new CreateTemplateFieldDto("A", "Blob", List.of(
                        new CreateTemplateRuleDto("SOMETHING", Map.of())))));

        assertThatThrownBy(() -> useCase.handle(request))
                .isInstanceOf(TemplateDefinitionException.class)
                .satisfies(e -> {
                    List<String> messages = ((TemplateDefinitionException) e).getErrors().stream()
                            .map(err -> err.message())
                            .toList();
                    assertThat(messages).anySatisfy(m -> assertThat(m).contains("Unknown data type"));
                    assertThat(messages).anySatisfy(m -> assertThat(m).contains("Unknown rule type"));
                });
    }

    @Test
    void duplicateFieldNamesAreRejected() {
        seedDataTypes();

        assertThatThrownBy(() -> useCase.handle(new CreateTemplateRequest(
                "Dup", null, List.of(
                new CreateTemplateFieldDto("Name", "Text", null),
                new CreateTemplateFieldDto("name", "Text", null)))))
                .isInstanceOf(TemplateDefinitionException.class)
                .hasMessageContaining("1 error(s)");
    }

    @Test
    void missingNameAndFieldsAreRejected() {
        assertThatThrownBy(() -> useCase.handle(new CreateTemplateRequest(" ", null, List.of())))
                .isInstanceOf(TemplateDefinitionException.class);
    }
}
