package com.modocument;

import com.modocument.infrastructure.persistence.ProcessStepJpaRepository;
import com.modocument.infrastructure.persistence.TemplateDataTypeJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.ValidationRuleJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the app on H2 and proves the seed data (ported from DbInitializer) loads. */
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadsAndSeedTest {

    @Autowired
    private TemplateJpaRepository templates;

    @Autowired
    private TemplateDataTypeJpaRepository dataTypes;

    @Autowired
    private TemplateFieldsJpaRepository templateFields;

    @Autowired
    private ProcessStepJpaRepository processSteps;

    @Autowired
    private ValidationRuleJpaRepository validationRules;

    @Test
    void seedDataIsLoaded() {
        assertThat(templates.count()).isEqualTo(4);
        assertThat(dataTypes.count()).isEqualTo(3);
        // 68 fields: ids 1-59 and 61-69 (60 is intentionally absent in the original seed).
        assertThat(templateFields.count()).isEqualTo(68);
        assertThat(processSteps.count()).isEqualTo(3);
        assertThat(validationRules.count()).isEqualTo(64);
    }

    @Test
    void employeeProfileTemplateHasFiftyFieldsAndItsRulesResolve() {
        assertThat(templateFields.findAll().stream()
                .filter(tf -> tf.getTemplateId() == 1)
                .count()).isEqualTo(50);
        assertThat(validationRules.findByTemplateId(1)).hasSize(64);
        assertThat(validationRules.findByTemplateId(1))
                .allSatisfy(rule -> assertThat(rule.getConfigJson()).isNotBlank());
    }
}
