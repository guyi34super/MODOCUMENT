package com.modocument.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.TemplateFields;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.TemplateFieldsJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ws23 template API: the template's {@code extractionMode} and each field's
 * extraction hints ({@code aliases}, {@code labelPattern}, {@code valuePattern},
 * {@code dateFormats}) round-trip through the existing create/update routes,
 * and author-supplied regexes are vetted by SafeRegex on save (400).
 * (The test profile applies template updates directly; maker/checker
 * payloads are covered by TemplateUpdateChangeApplierModeTest.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateExtractionHintsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private TemplateFieldsJpaRepository fields;
    @Autowired
    private JwtService jwtService;

    private String auth;

    @BeforeEach
    void writer() {
        AppUser user = new AppUser();
        user.setEmail("ws23-hints-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("{noop}irrelevant");
        user.setDisplayName("ws23 hints");
        user.setBankId(1);
        user.setRole(Role.READ_WRITE);
        user.setSettingsJson("{}");
        auth = "Bearer " + jwtService.issueToken(users.save(user));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               Object body) throws Exception {
        return mvc.perform(request.header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String text ? text : JSON.writeValueAsString(body)));
    }

    private int createTemplate(String extractionModeJson) throws Exception {
        String body = "{\"name\":\"ws23-" + UUID.randomUUID() + "\",\"description\":\"d\""
                + (extractionModeJson == null ? "" : ",\"extractionMode\":" + extractionModeJson) + "}";
        String response = send(post("/Templates/Create"), body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("templateId").asInt();
    }

    private JsonNode getTemplate(int id) throws Exception {
        return JSON.readTree(mvc.perform(get("/Templates/GetById/" + id).header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ---- template extraction mode ----------------------------------------------

    @Test
    void theExtractionModeRoundTripsAndAnUpdateWithoutItKeepsIt() throws Exception {
        int id = createTemplate("\"code_only\"");
        assertThat(getTemplate(id).get("extractionMode").asText()).isEqualTo("CODE_ONLY");

        send(put("/Templates/Update"), "{\"templateId\":" + id + ",\"name\":\"ws23-renamed-"
                + UUID.randomUUID() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.extractionMode").value("CODE_ONLY"));
        assertThat(templates.findById(id).orElseThrow().getExtractionMode()).isEqualTo("CODE_ONLY");

        send(put("/Templates/Update"), "{\"templateId\":" + id + ",\"name\":\"ws23-again-"
                + UUID.randomUUID() + "\",\"extractionMode\":\"AI_ONLY\"}")
                .andExpect(status().isCreated());
        assertThat(getTemplate(id).get("extractionMode").asText()).isEqualTo("AI_ONLY");

        // An explicit null goes back to the bank default.
        send(put("/Templates/Update"), "{\"templateId\":" + id + ",\"name\":\"ws23-inherit-"
                + UUID.randomUUID() + "\",\"extractionMode\":null}")
                .andExpect(status().isCreated());
        assertThat(getTemplate(id).get("extractionMode").isNull()).isTrue();
    }

    @Test
    void anUnknownExtractionModeIs400() throws Exception {
        send(post("/Templates/Create"), "{\"name\":\"ws23-bad-" + UUID.randomUUID()
                + "\",\"extractionMode\":\"MAGIC\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Unknown extraction mode")));

        int id = createTemplate(null);
        send(put("/Templates/Update"), "{\"templateId\":" + id + ",\"name\":\"x-" + UUID.randomUUID()
                + "\",\"extractionMode\":\"MAGIC\"}")
                .andExpect(status().isBadRequest());
        assertThat(getTemplate(id).get("extractionMode").isNull()).isTrue();
    }

    // ---- field extraction hints ---------------------------------------------------

    private ObjectNode field(int templateId, String name) {
        ObjectNode body = JSON.createObjectNode();
        body.put("templateId", templateId);
        body.put("fieldName", name);
        body.put("templateDataTypeId", 1);
        return body;
    }

    @Test
    void fieldHintsRoundTripAndAnUpdateKeepsTheHintsItDoesNotName() throws Exception {
        int templateId = createTemplate(null);
        ObjectNode body = field(templateId, "Reference");
        body.putArray("aliases").add("Ref").add(" Ref No ").add("");
        body.put("labelPattern", "Ref(erence)?\\.?");
        body.put("valuePattern", "REF-(\\d{4})");
        body.putArray("dateFormats").add("dd.MM.yyyy");

        String created = send(post("/TemplateFields/Create"), body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int fieldId = JSON.readTree(created).get("templateFieldsId").asInt();

        mvc.perform(get("/TemplateFields/GetById/" + fieldId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliases.length()").value(2))
                .andExpect(jsonPath("$.aliases[1]").value("Ref No"))
                .andExpect(jsonPath("$.labelPattern").value("Ref(erence)?\\.?"))
                .andExpect(jsonPath("$.valuePattern").value("REF-(\\d{4})"))
                .andExpect(jsonPath("$.dateFormats[0]").value("dd.MM.yyyy"));
        mvc.perform(get("/TemplateFields/GetAll").param("templateId", String.valueOf(templateId))
                        .header("Authorization", auth))
                .andExpect(jsonPath("$[0].aliases[0]").value("Ref"));

        ObjectNode update = field(templateId, "Reference");
        update.put("templateFieldsId", fieldId);
        update.putArray("aliases").add("Reference No");
        send(put("/TemplateFields/Update/" + fieldId), update)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aliases[0]").value("Reference No"))
                .andExpect(jsonPath("$.labelPattern").value("Ref(erence)?\\.?"));

        TemplateFields stored = fields.findById(fieldId).orElseThrow();
        assertThat(stored.getAliases()).containsExactly("Reference No");
        assertThat(stored.getValuePattern()).isEqualTo("REF-(\\d{4})");
        assertThat(stored.getDateFormats()).containsExactly("dd.MM.yyyy");

        // Explicit empties clear them.
        ObjectNode clear = field(templateId, "Reference");
        clear.put("templateFieldsId", fieldId);
        clear.putNull("labelPattern");
        clear.putArray("dateFormats");
        send(put("/TemplateFields/Update/" + fieldId), clear).andExpect(status().isCreated());
        stored = fields.findById(fieldId).orElseThrow();
        assertThat(stored.getLabelPattern()).isNull();
        assertThat(stored.getDateFormats()).isEmpty();
        assertThat(stored.getAliases()).containsExactly("Reference No");
    }

    @Test
    void dangerousOrInvalidHintsAre400AndNothingIsSaved() throws Exception {
        int templateId = createTemplate(null);
        long before = fields.count();

        ObjectNode nested = field(templateId, "A");
        nested.put("valuePattern", "(a+)+$");
        send(post("/TemplateFields/Create"), nested)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("valuePattern")))
                .andExpect(jsonPath("$.error", containsString("exponential")));

        ObjectNode broken = field(templateId, "B");
        broken.put("labelPattern", "Ref([");
        send(post("/TemplateFields/Create"), broken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("labelPattern")));

        ObjectNode longPattern = field(templateId, "C");
        longPattern.put("labelPattern", "a".repeat(501));
        send(post("/TemplateFields/Create"), longPattern)
                .andExpect(status().isBadRequest());

        ObjectNode badDate = field(templateId, "D");
        badDate.putArray("dateFormats").add("dd/MM/yyyy{");
        send(post("/TemplateFields/Create"), badDate)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("dateFormats")));

        ObjectNode tooMany = field(templateId, "E");
        Collections.nCopies(51, "x").forEach(tooMany.putArray("aliases")::add);
        send(post("/TemplateFields/Create"), tooMany)
                .andExpect(status().isBadRequest());

        assertThat(fields.count()).isEqualTo(before);
    }

    @Test
    void anUpdateWithADangerousPatternIs400AndKeepsTheStoredOne() throws Exception {
        int templateId = createTemplate(null);
        ObjectNode body = field(templateId, "Amount");
        body.put("valuePattern", "(\\d+\\.\\d{2})");
        int fieldId = JSON.readTree(send(post("/TemplateFields/Create"), body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("templateFieldsId").asInt();

        ObjectNode update = field(templateId, "Amount");
        update.put("templateFieldsId", fieldId);
        update.put("valuePattern", "(\\w+\\s?)*$");
        send(put("/TemplateFields/Update/" + fieldId), update).andExpect(status().isBadRequest());

        assertThat(fields.findById(fieldId).orElseThrow().getValuePattern()).isEqualTo("(\\d+\\.\\d{2})");
    }

    // ---- composite create -----------------------------------------------------------

    @Test
    void createWithFieldsCarriesTheModeAndHints() throws Exception {
        String body = """
                {"name": "ws23-composite-%s", "extractionMode": "AI_WITH_CODE_FALLBACK", "fields": [
                  {"fieldName": "Opened", "dataType": "Date", "dateFormats": ["dd.MM.yyyy"],
                   "aliases": ["Open date"]},
                  {"fieldName": "Reference", "dataType": "Text", "labelPattern": "Ref\\\\.?",
                   "valuePattern": "(REF-\\\\d+)"}
                ]}""".formatted(UUID.randomUUID());

        String response = send(post("/Templates/CreateWithFields"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.extractionMode").value("AI_WITH_CODE_FALLBACK"))
                .andExpect(jsonPath("$.fields[0].dateFormats[0]").value("dd.MM.yyyy"))
                .andExpect(jsonPath("$.fields[0].aliases[0]").value("Open date"))
                .andExpect(jsonPath("$.fields[1].labelPattern").value("Ref\\.?"))
                .andExpect(jsonPath("$.fields[1].valuePattern").value("(REF-\\d+)"))
                .andReturn().getResponse().getContentAsString();
        int templateId = JSON.readTree(response).get("templateId").asInt();

        assertThat(templates.findById(templateId).orElseThrow().getExtractionMode())
                .isEqualTo("AI_WITH_CODE_FALLBACK");
        List<TemplateFields> stored = fields.findAll().stream()
                .filter(f -> f.getTemplateId() == templateId).toList();
        assertThat(stored).extracting(TemplateFields::getFieldName)
                .containsExactlyInAnyOrder("Opened", "Reference");
        assertThat(stored).filteredOn(f -> f.getFieldName().equals("Reference"))
                .singleElement().extracting(TemplateFields::getLabelPattern).isEqualTo("Ref\\.?");
    }

    @Test
    void createWithFieldsRefusesADangerousPatternOrModeWithItsFieldNamed() throws Exception {
        String body = """
                {"name": "ws23-bad-%s", "extractionMode": "SOMETIMES", "fields": [
                  {"fieldName": "Code", "dataType": "Text", "valuePattern": "(x*)*y"}
                ]}""".formatted(UUID.randomUUID());

        send(post("/Templates/CreateWithFields"), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.fieldName == 'Code')].message",
                        org.hamcrest.Matchers.hasItem(containsString("valuePattern"))))
                .andExpect(jsonPath("$.errors[*].message",
                        org.hamcrest.Matchers.hasItem(containsString("Unknown extraction mode"))));
    }
}
