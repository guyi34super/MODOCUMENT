package com.modocument;

import com.modocument.infrastructure.persistence.ProcessStepJpaRepository;
import com.modocument.infrastructure.persistence.TemplateDataTypeJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.ValidationRuleJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the app on H2 and proves only lookup data is seeded — templates, fields
 * and validation rules start empty and are created by users through the UI.
 */
@SpringBootTest
@AutoConfigureMockMvc // same context configuration as the API tests (one cached context)
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
        assertThat(templates.count()).isZero();
        assertThat(templateFields.count()).isZero();
        assertThat(validationRules.count()).isZero();
        assertThat(dataTypes.count()).isEqualTo(3);
        assertThat(processSteps.count()).isEqualTo(3);
    }
}
