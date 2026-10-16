package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.application.usecase.TemplateOwnership;
import com.modocument.doccreation.dto.ImportRulesRequest;
import com.modocument.doccreation.dto.RuleInput;
import com.modocument.doccreation.dto.TestRulesRequest;
import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;

import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import com.modocument.doccreation.rules.DocRuleEvaluator;
import com.modocument.doccreation.rules.DocRuleType;
import com.modocument.doccreation.rules.RegionView;
import com.modocument.doccreation.rules.RuleTypeCatalog;
import com.modocument.domain.entity.ValidationRule;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.RuleType;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DocFieldRuleUseCase without a database: {@code /rules/test} through the
 * {@link DocRuleEvaluator} interface (saved or unsaved rules, checked like a
 * save, nothing stored), the import's extra {@code validation:read}, the
 * flow rule conversion table and the rule type catalogue.
 */
class DocFieldRuleUseCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DocCreationOwnership ownership = mock(DocCreationOwnership.class);
    private final DocFieldRuleJpaRepository rules = mock(DocFieldRuleJpaRepository.class);
    private final TemplateVersions versions = mock(TemplateVersions.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DocRuleEvaluator> evaluatorProvider = mock(ObjectProvider.class);
    private final List<Call> calls = new ArrayList<>();
    private DocFieldRuleUseCase useCase;
    private DocBlueprint blueprint;

    private record Call(List<RegionView> regions, Map<String, String> values, Set<String> images,
                        List<DocFieldRule> rules, int revision) {
    }

    @BeforeEach
    void wire() {
        useCase = new DocFieldRuleUseCase(ownership, rules, versions, mock(BlueprintSourceUseCase.class),
                mock(TemplateOwnership.class), mock(TemplateRepository.class), mock(CurrentUserProvider.class),
                mock(BlueprintMappingSuggester.class), mock(ValidationRuleRepository.class), audit,
                evaluatorProvider);
        blueprint = new DocBlueprint();
        blueprint.setDocBlueprintId(7);
        blueprint.setBankId(3);
        blueprint.setCurrentVersion(2);
        blueprint.setRulesRevision(5);
        when(ownership.requireTemplate(7)).thenReturn(blueprint);
        when(versions.regions(blueprint, 2)).thenReturn(List.of(
                FakePdfTemplateEngine.region("dob", "Date of birth", null, RegionKind.DATE,
                        new PdfBox(0, 10, 10, 100, 20), 1, null),
                FakePdfTemplateEngine.region("status", "Status", null, RegionKind.CHOICE,
                        new PdfBox(0, 10, 40, 100, 20), 0, null)));
        when(versions.fieldProps(blueprint, 2)).thenReturn(Map.of("status",
                new FieldProps(null, null, null, List.of("A", "B"), null, null, null)));
        DocRuleEvaluator evaluator = (regions, values, images, ruleList, revision) -> {
            calls.add(new Call(regions, values, images, ruleList, revision));
            return new ValidationResultDto("DRAFT", null, revision, false,
                    List.of(new ValidationResultDto.Issue("dob", "Date of birth", null, "DATE_INVALID", "x")),
                    List.of(), List.of(), true);
        };
        when(evaluatorProvider.getIfAvailable()).thenReturn(evaluator);
    }

    private static DocFieldRule saved(long id, String key, String type) {
        DocFieldRule rule = new DocFieldRule();
        rule.setDocFieldRuleId(id);
        rule.setRegionKey(key);
        rule.setRuleType(type);
        return rule;
    }

    @Test
    void testChecksTheSavedRulesWithoutPhotosAndStoresNothing() {
        DocFieldRule required = saved(11, "dob", "REQUIRED");
        when(rules.findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(3, 7))
                .thenReturn(List.of(required));

        ValidationResultDto result = useCase.test(7, new TestRulesRequest(Map.of("dob", "31/12/2026"), null));

        assertThat(result.errors()).extracting(ValidationResultDto.Issue::code).containsExactly("DATE_INVALID");
        Call call = calls.get(0);
        assertThat(call.regions()).extracting(RegionView::key).as("in sort order").containsExactly("status", "dob");
        assertThat(call.regions().get(0).props().options()).containsExactly("A", "B");
        assertThat(call.regions().get(1).props()).isEqualTo(FieldProps.NONE);
        assertThat(call.values()).containsEntry("dob", "31/12/2026");
        assertThat(call.images()).isEmpty();
        assertThat(call.rules()).containsExactly(required);
        assertThat(call.revision()).isEqualTo(5);
        verifyNoInteractions(audit);
    }

    @Test
    void unsavedRulesAreCheckedLikeASaveAndTestedInstead() throws Exception {
        RuleInput range = new RuleInput("dob", "DATE_RANGE", MAPPER.readTree("{\"Max\":\"today\"}"), null, null,
                null, null, null);
        useCase.test(7, new TestRulesRequest(null, List.of(range)));
        DocFieldRule tested = calls.get(0).rules().get(0);
        assertThat(tested.getDocFieldRuleId()).isNull();
        assertThat(tested.getRuleType()).isEqualTo("DATE_RANGE");
        assertThat(tested.getBlueprintId()).isEqualTo(7);
        assertThat(calls.get(0).values()).isEmpty();

        RuleInput bad = new RuleInput("dob", "COMPARE", MAPPER.readTree("{\"Field\":\"dob\",\"Op\":\"<\"}"), null,
                null, null, null, null);
        assertThatThrownBy(() -> useCase.test(7, new TestRulesRequest(Map.of(), List.of(bad))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not the field itself");
        assertThatThrownBy(() -> useCase.test(7, new TestRulesRequest(Map.of("dob", "x".repeat(20001)), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withoutAnEvaluatorTheTestRouteIsUnavailable() {
        when(evaluatorProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> useCase.test(7, new TestRulesRequest(Map.of(), null)))
                .isInstanceOfSatisfying(DocCreationException.class, e -> assertThat(e.status()).isEqualTo(503));
    }

    @Test
    void importingNeedsValidationRead() {
        when(ownership.callerHas(Permission.VALIDATION_READ)).thenReturn(false);
        assertThatThrownBy(() -> useCase.importPreview(7, null)).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("validation:read");
        assertThatThrownBy(() -> useCase.importRules(7, new ImportRulesRequest(5, null, null, "MERGE")))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(rules);
        when(ownership.requireTemplate(anyInt())).thenThrow(new java.util.NoSuchElementException("gone"));
        assertThatThrownBy(() -> useCase.importPreview(9, null)).as("permission before ownership")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void flowRulesAreConvertedByTheTable() {
        assertThat(convert(RuleType.REQUIRED, "{}").type()).isEqualTo(DocRuleType.REQUIRED);
        assertThat(convert(RuleType.EXPIRY_DATE, "").type()).isEqualTo(DocRuleType.EXPIRY_DATE);
        DocFieldRuleUseCase.Converted date = convert(RuleType.DATE_FORMAT, "{\"Format\":\"dd/MM\"}");
        assertThat(date.config().toString()).isEqualTo("{\"Formats\":[\"yyyy-MM-dd\"]}");
        assertThat(convert(RuleType.REGEX, "{\"Pattern\":\"" + DocFieldRuleUseCase.FLOW_EMAIL_PATTERN
                .replace("\\", "\\\\") + "\"}").type()).isEqualTo(DocRuleType.EMAIL);
        DocFieldRuleUseCase.Converted regex = convert(RuleType.REGEX, "{\"Pattern\":\"^[0-9]{9}$\"}");
        assertThat(regex.type()).isEqualTo(DocRuleType.REGEX);
        assertThat(regex.config().get("Pattern").asText()).isEqualTo("^[0-9]{9}$");
        assertThat(regex.message()).as("generic for an unknown source field").isNull();
        assertThat(convert(RuleType.RANGE, "{\"Min\":\"1.5\",\"Max\":9}").config().toString())
                .isEqualTo("{\"Min\":1.5,\"Max\":9}");

        assertSkipped(RuleType.RANGE, "{\"Min\":9,\"Max\":1}", "its range is not valid");
        assertSkipped(RuleType.RANGE, "{}", "its range is not valid");
        assertSkipped(RuleType.REGEX, "{\"Pattern\":\"([a-\"}", "its pattern is not valid");
        assertSkipped(RuleType.CONDITIONAL_REQUIREMENT, "{}", "can't be imported");
        assertSkipped(RuleType.TYPE, "{}", "can't be imported");
        assertSkipped(RuleType.CUSTOM, "{}", "can't be imported");
    }

    @Test
    void theDayforceMessageIsKeptOnlyWhenSpecific() {
        assertThat(DocFieldRuleUseCase.regexMessage("SIN", "^[1-79][0-9]{8}$"))
                .startsWith("SIN must have 9 digits");
        assertThat(DocFieldRuleUseCase.regexMessage("customer_code", "^[A-Z]{3}$")).isNull();
        assertThat(DocFieldRuleUseCase.regexMessage(null, "x")).isNull();
        String longest = DocFieldRuleUseCase.regexMessage("ProvincialSafetyPlans", "^.$");
        assertThat(longest).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void theCatalogueMatchesTheContract() {
        assertThat(RuleTypeCatalog.all()).hasSize(DocRuleType.values().length);
        for (RegionKind photo : List.of(RegionKind.IMAGE, RegionKind.SIGNATURE)) {
            assertThat(RuleTypeCatalog.all()).filteredOn(e -> e.kinds().contains(photo))
                    .extracting(RuleTypeCatalog.Entry::type).containsExactly(DocRuleType.REQUIRED);
        }
        assertThat(RuleTypeCatalog.all()).filteredOn(e -> e.kinds().contains(RegionKind.CHECKBOX))
                .extracting(RuleTypeCatalog.Entry::type)
                .containsExactly(DocRuleType.REQUIRED, DocRuleType.REQUIRED_IF);
        assertThat(RuleTypeCatalog.of(DocRuleType.DATE_RANGE).kinds())
                .containsExactly(RegionKind.DATE, RegionKind.TEXT);
        assertThat(RuleTypeCatalog.of(DocRuleType.ALLOWED_VALUES).configKeys())
                .containsExactly("Values", "IgnoreCase");
        assertThat(RuleTypeCatalog.fits(DocRuleType.EMAIL, RegionKind.NUMBER)).isFalse();
        assertThat(RuleTypeCatalog.fits(DocRuleType.REQUIRED, null)).isFalse();
    }

    private static DocFieldRuleUseCase.Converted convert(RuleType type, String config) {
        return DocFieldRuleUseCase.convert(new ValidationRule(1, "f", type, config));
    }

    private static void assertSkipped(RuleType type, String config, String reason) {
        assertThatThrownBy(() -> convert(type, config)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(reason);
    }
}
