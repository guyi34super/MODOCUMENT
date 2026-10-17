package com.modocument.doccreation.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.doccreation.entity.DocBlueprint;
import com.modocument.doccreation.entity.DocDataFieldRule;
import com.modocument.doccreation.entity.DocDataTemplate;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.repo.DocDataFieldRuleJpaRepository;
import com.modocument.doccreation.repo.DocDataTemplateJpaRepository;
import com.modocument.doccreation.repo.DocFieldRuleJpaRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EffectiveRules without a database (data-templates-contract §4): one
 * transient copy per mapped region, negative ids, origin DATA_TEMPLATE,
 * REQUIRED_IF/COMPARE {@code Field} re-keyed to the first region mapped to
 * the field (or to a key no region has), the PDF's own rules kept after
 * them, and the mapping JSON helpers.
 */
class EffectiveRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static DocDataFieldRule rule(long id, String field, String type, String config) {
        DocDataFieldRule rule = new DocDataFieldRule();
        rule.setDocDataFieldRuleId(id);
        rule.setBankId(3);
        rule.setDataTemplateId(9);
        rule.setFieldName(field);
        rule.setRuleType(type);
        rule.setConfigJson(config);
        rule.setSeverity("WARNING");
        rule.setOrigin("IMPORTED");
        rule.setSourceTemplateId(44);
        rule.setSourceFieldName("Date_Of_Birth");
        rule.setSortOrder(2);
        return rule;
    }

    private static DocBlueprint blueprint(String mappingJson, Integer dataTemplateId) {
        DocBlueprint blueprint = new DocBlueprint();
        blueprint.setDocBlueprintId(7);
        blueprint.setBankId(3);
        blueprint.setDataTemplateId(dataTemplateId);
        blueprint.setDataMappingJson(mappingJson);
        return blueprint;
    }

    @Test
    void dataRulesAreCopiedOntoEveryMappedRegionAndReferencesAreRekeyed() throws Exception {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("dob_page1", "dob");
        mapping.put("dob_page2", "dob");
        mapping.put("nic", "national_id");
        List<DocFieldRule> out = EffectiveRules.rekey(List.of(
                rule(5, "dob", "DATE_RANGE", "{\"Max\":\"-18y\"}"),
                rule(6, "national_id", "REQUIRED_IF", "{\"Field\":\"dob\",\"Equals\":\"x\"}"),
                rule(7, "national_id", "COMPARE", "{\"Field\":\"expiry\",\"Op\":\">\"}"),
                rule(8, "unmapped", "REQUIRED", "{}")), mapping, blueprint(null, 9));

        assertThat(out).extracting(DocFieldRule::getRegionKey, DocFieldRule::getDocFieldRuleId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("dob_page1", -5L),
                        org.assertj.core.groups.Tuple.tuple("dob_page2", -5L),
                        org.assertj.core.groups.Tuple.tuple("nic", -6L),
                        org.assertj.core.groups.Tuple.tuple("nic", -7L));
        DocFieldRule first = out.get(0);
        assertThat(first.getOrigin()).isEqualTo(EffectiveRules.ORIGIN_DATA_TEMPLATE);
        assertThat(first.getBlueprintId()).isEqualTo(7);
        assertThat(first.getBankId()).isEqualTo(3);
        assertThat(first.getSeverity()).isEqualTo("WARNING");
        assertThat(first.getSourceTemplateId()).isEqualTo(44);
        assertThat(first.getSourceFieldName()).isEqualTo("Date_Of_Birth");
        assertThat(first.getSortOrder()).isEqualTo(2);
        assertThat(JSON.readTree(first.getConfigJson()).get("Max").asText()).isEqualTo("-18y");
        assertThat(JSON.readTree(out.get(2).getConfigJson()).get("Field").asText())
                .as("the first region mapped to dob").isEqualTo("dob_page1");
        assertThat(JSON.readTree(out.get(2).getConfigJson()).get("Equals").asText()).isEqualTo("x");
        assertThat(JSON.readTree(out.get(3).getConfigJson()).get("Field").asText())
                .as("a field no region shows: never a region key")
                .startsWith(EffectiveRules.UNMAPPED_FIELD_PREFIX)
                .doesNotMatch("[a-z0-9_]{1,64}");
    }

    @Test
    void theInheritedRulesComeBeforeThePdfsOwnAndNeedAChosenDataTemplate() {
        DocFieldRuleJpaRepository pdfRules = mock(DocFieldRuleJpaRepository.class);
        DocDataTemplateJpaRepository templates = mock(DocDataTemplateJpaRepository.class);
        DocDataFieldRuleJpaRepository dataRules = mock(DocDataFieldRuleJpaRepository.class);
        EffectiveRules effective = new EffectiveRules(pdfRules, templates, dataRules);
        DocFieldRule own = new DocFieldRule();
        own.setDocFieldRuleId(1L);
        own.setRegionKey("notes");
        own.setRuleType("LENGTH");
        when(pdfRules.findByBankIdAndBlueprintIdOrderByRegionKeyAscSortOrderAscDocFieldRuleIdAsc(3, 7))
                .thenReturn(List.of(own));
        DocDataTemplate template = new DocDataTemplate();
        template.setDocDataTemplateId(9);
        template.setBankId(3);
        template.setName("Customer data");
        when(templates.findByDocDataTemplateIdAndBankId(9, 3)).thenReturn(Optional.of(template));
        when(dataRules.findByBankIdAndDataTemplateIdOrderByFieldNameAscSortOrderAscDocDataFieldRuleIdAsc(3, 9))
                .thenReturn(List.of(rule(5, "full_name", "REQUIRED", "{}")));

        EffectiveRules.Resolved resolved = effective.resolve(blueprint("{\"name\":\"full_name\"}", 9));
        assertThat(resolved.rules()).extracting(DocFieldRule::getRegionKey).containsExactly("name", "notes");
        assertThat(resolved.inherited()).hasSize(1);
        assertThat(resolved.dataTemplateName()).isEqualTo("Customer data");
        assertThat(EffectiveRules.requiredKeys(resolved.rules())).containsExactly("name");

        EffectiveRules.Resolved none = effective.resolve(blueprint("{\"name\":\"full_name\"}", null));
        assertThat(none.rules()).containsExactly(own);
        assertThat(none.inherited()).isEmpty();
        assertThat(none.dataTemplateName()).isNull();
    }

    @Test
    void theMappingJsonDropsBlankEntriesAndAnEmptyMappingIsNull() {
        assertThat(EffectiveRules.readMapping("{\"a\":\"x\",\"b\":\"\",\"c\":null}")).containsExactly(
                Map.entry("a", "x"));
        assertThat(EffectiveRules.readMapping("not json")).isEmpty();
        assertThat(EffectiveRules.readMapping(null)).isEmpty();
        assertThat(EffectiveRules.writeMapping(Map.of())).isNull();
        assertThat(EffectiveRules.writeMapping(Map.of("a", "x"))).isEqualTo("{\"a\":\"x\"}");
    }
}
