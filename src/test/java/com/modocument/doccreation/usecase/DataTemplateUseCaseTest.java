package com.modocument.doccreation.usecase;

import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.TemplateDataTypeRepository;
import com.modocument.application.port.TemplateFieldsRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.doccreation.dto.CopyFlowTemplateRequest;
import com.modocument.doccreation.dto.DataFieldInput;
import com.modocument.doccreation.entity.DocDataField;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.repo.DocBlueprintJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldJpaRepository;
import com.modocument.doccreation.repo.DocDataFieldRuleJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.doccreation.rules.DocRuleEvaluator;
import com.modocument.domain.model.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DataTemplateUseCase without a database: copying from a flow template
 * needs template:read and validation:read (checked before anything is read,
 * 403), the data type to kind table, unique slugged names and the field
 * checks (the region property checker, with its {@code Field "<name>": }
 * messages).
 */
class DataTemplateUseCaseTest {

    private final DocCreationOwnership ownership = mock(DocCreationOwnership.class);
    private final TemplateOwnership templateOwnership = mock(TemplateOwnership.class);
    private final TemplateFieldsRepository templateFields = mock(TemplateFieldsRepository.class);
    private final ValidationRuleRepository flowRules = mock(ValidationRuleRepository.class);
    private final DocDataTemplateJpaRepository dataTemplates = mock(DocDataTemplateJpaRepository.class);
    private DataTemplateUseCase useCase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        useCase = new DataTemplateUseCase(dataTemplates, mock(DocDataFieldJpaRepository.class),
                mock(DocDataFieldRuleJpaRepository.class), mock(DocBlueprintJpaRepository.class), ownership,
                templateOwnership, mock(TemplateRepository.class), templateFields,
                mock(TemplateDataTypeRepository.class), flowRules, mock(AuditRecorder.class),
                mock(ObjectProvider.class));
        when(ownership.callerHas(Permission.TEMPLATE_READ)).thenReturn(true);
        when(ownership.callerHas(Permission.VALIDATION_READ)).thenReturn(true);
    }

    @Test
    void copyingNeedsValidationReadAndTemplateRead() {
        when(ownership.callerHas(Permission.VALIDATION_READ)).thenReturn(false);
        assertThatThrownBy(() -> useCase.copyPreview(4)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("validation:read");
        assertThatThrownBy(() -> useCase.copyFromFlowTemplate(new CopyFlowTemplateRequest(4, "Copy", null)))
                .isInstanceOf(AccessDeniedException.class);

        when(ownership.callerHas(Permission.VALIDATION_READ)).thenReturn(true);
        when(ownership.callerHas(Permission.TEMPLATE_READ)).thenReturn(false);
        assertThatThrownBy(() -> useCase.copyPreview(4)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("template:read");
        verifyNoInteractions(templateOwnership, templateFields, flowRules, dataTemplates);
    }

    @Test
    void theDataTypeGivesTheKind() {
        assertThat(DataTemplateUseCase.kindOf("Number")).isEqualTo(RegionKind.NUMBER);
        assertThat(DataTemplateUseCase.kindOf("date")).isEqualTo(RegionKind.DATE);
        assertThat(DataTemplateUseCase.kindOf("Text")).isEqualTo(RegionKind.TEXT);
        assertThat(DataTemplateUseCase.kindOf("Boolean")).isEqualTo(RegionKind.TEXT);
        assertThat(DataTemplateUseCase.kindOf(null)).isEqualTo(RegionKind.TEXT);
    }

    @Test
    void copiedNamesAreUnique() {
        Set<String> used = new HashSet<>();
        assertThat(DataTemplateUseCase.uniqueName("full_name", used)).isEqualTo("full_name");
        assertThat(DataTemplateUseCase.uniqueName("full_name", used)).isEqualTo("full_name_2");
        assertThat(DataTemplateUseCase.uniqueName("full_name", used)).isEqualTo("full_name_3");
        assertThat(DataTemplateUseCase.uniqueName("", used)).isEqualTo("field");
        assertThat(DataTemplateUseCase.uniqueName(OverlayMapper.slug("Date of Birth!"), used))
                .isEqualTo("date_of_birth");
    }

    @Test
    void fieldsAreCheckedLikeRegions() {
        List<DocDataField> checked = DataTemplateUseCase.checkFields(List.of(
                new DataFieldInput(" status ", " Status ", "choice", "none",
                        new FieldProps("Pick", null, "Single", List.of(" Single ", "Married"), "dd/MM/yyyy", null,
                                null), "Status"),
                new DataFieldInput("dob", "Date of birth", "DATE", null, null, null)));
        assertThat(checked).extracting(DocDataField::getFieldName).containsExactly("status", "dob");
        DocDataField status = checked.get(0);
        assertThat(status.getKind()).isEqualTo("CHOICE");
        assertThat(status.getLabel()).isEqualTo("Status");
        assertThat(status.getPiiKind()).isEqualTo("NONE");
        assertThat(status.getPropsJson()).as("trimmed options, the date format of another kind dropped")
                .isEqualTo("{\"placeholder\":\"Pick\",\"defaultValue\":\"Single\",\"options\":[\"Single\","
                        + "\"Married\"]}");
        assertThat(status.getSortOrder()).isZero();
        assertThat(checked.get(1).getPropsJson()).isNull();
        assertThat(checked.get(1).getSortOrder()).isEqualTo(1);

        assertThatThrownBy(() -> DataTemplateUseCase.checkFields(List.of(
                new DataFieldInput("status", "Status", "CHOICE", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Field \"status\": ");
        assertThatThrownBy(() -> DataTemplateUseCase.checkFields(List.of(
                new DataFieldInput("sig", "Signature", "SIGNATURE", null, null, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("the kind must be one of");
    }
}
