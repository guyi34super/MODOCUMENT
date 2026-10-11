package com.modocument.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.domain.model.PiiKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
// SCHEMA PARITY with V30 (uq_templatefields_templateid_fieldname): no two
// fields of one template share a name. The app blocks duplicates up front
// (CreateTemplateWithFieldsUseCase); this is the DB truth for the race.
@Table(name = "TemplateFields", uniqueConstraints = @UniqueConstraint(
        name = "uq_templatefields_templateid_fieldname",
        columnNames = {"TemplateId", "FieldName"}))
// SCHEMA PARITY with V100 (ck_templatefields_piikind) on the H2 test schema:
// the PiiKind vocabulary is closed — see com.modocument.domain.model.PiiKind.
// NULL stays allowed as the "unclassified yet" state.
@Check(name = "ck_templatefields_piikind",
        constraints = "PiiKind in ('NIC', 'ACCOUNT_NUMBER', 'EMAIL', 'PHONE', 'NAME', 'NONE')")
public class TemplateFields {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HINT_ALIASES = "aliases";
    private static final String HINT_LABEL_PATTERN = "labelPattern";
    private static final String HINT_VALUE_PATTERN = "valuePattern";
    private static final String HINT_DATE_FORMATS = "dateFormats";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateFieldsId")
    private int templateFieldsId;

    @Column(name = "TemplateId")
    private int templateId;

    /** The owning template's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    /** Doubles as the request schema cap when the entity arrives as a body. */
    @Size(max = 255)
    @Column(name = "FieldName")
    private String fieldName;

    @Column(name = "TemplateDataTypeId")
    private int templateDataTypeId;

    /**
     * PII classification (V100, PRIV-004): drives the role-by-channel
     * masking (gate C2). NULL = not classified yet — the first read that
     * needs it persists {@code PiiClassifier}'s inference here; an explicit
     * value (including {@link PiiKind#NONE}) is never overwritten.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "PiiKind", length = 20)
    private PiiKind piiKind;

    /*
     * ws23 extraction hints for the code extractor (V230). The two lists are
     * stored as JSON arrays of strings and travel as arrays in the API; the
     * patterns are vetted by SafeRegex on save (TemplateFieldHints) and again
     * before every use.
     */

    /** Alternative header/label texts, matched case-insensitively (JSON array). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "Aliases")
    private String aliasesJson;

    /** Regex for the field's label in label: value documents. */
    @Column(name = "LabelPattern", length = 500)
    private String labelPattern;

    /** Regex for the field's value; capture group 1 when present. */
    @Column(name = "ValuePattern", length = 500)
    private String valuePattern;

    /** DateTimeFormatter patterns tried before the built-in date formats (JSON array). */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "DateFormats")
    private String dateFormatsJson;

    /**
     * Which hint properties a request body named (Jackson calls a setter only
     * for present properties): an update keeps every hint it does not name.
     */
    @Transient
    private Set<String> providedHints = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY) // ws28: fetched by the repositories that need it
    @JoinColumn(name = "TemplateId", insertable = false, updatable = false)
    private Template template;

    @ManyToOne(fetch = FetchType.LAZY) // ws28: fetched by the repositories that need it
    @JoinColumn(name = "TemplateDataTypeId", insertable = false, updatable = false)
    private TemplateDataType templateDataType;

    public TemplateFields() {
    }

    public int getTemplateFieldsId() {
        return templateFieldsId;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public void setTemplateFieldsId(int templateFieldsId) {
        this.templateFieldsId = templateFieldsId;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public PiiKind getPiiKind() {
        return piiKind;
    }

    public void setPiiKind(PiiKind piiKind) {
        this.piiKind = piiKind;
    }

    public int getTemplateDataTypeId() {
        return templateDataTypeId;
    }

    public void setTemplateDataTypeId(int templateDataTypeId) {
        this.templateDataTypeId = templateDataTypeId;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }

    public TemplateDataType getTemplateDataType() {
        return templateDataType;
    }

    public void setTemplateDataType(TemplateDataType templateDataType) {
        this.templateDataType = templateDataType;
    }

    /** Alternative header/label texts; empty when none. */
    public List<String> getAliases() {
        return readList(aliasesJson);
    }

    /** Null or empty clears the aliases. */
    public void setAliases(List<String> aliases) {
        this.aliasesJson = writeList(aliases);
        providedHints.add(HINT_ALIASES);
    }

    public String getLabelPattern() {
        return labelPattern;
    }

    public void setLabelPattern(String labelPattern) {
        this.labelPattern = blankToNull(labelPattern);
        providedHints.add(HINT_LABEL_PATTERN);
    }

    public String getValuePattern() {
        return valuePattern;
    }

    public void setValuePattern(String valuePattern) {
        this.valuePattern = blankToNull(valuePattern);
        providedHints.add(HINT_VALUE_PATTERN);
    }

    /** Accepted date formats; empty when none. */
    public List<String> getDateFormats() {
        return readList(dateFormatsJson);
    }

    /** Null or empty clears the formats. */
    public void setDateFormats(List<String> dateFormats) {
        this.dateFormatsJson = writeList(dateFormats);
        providedHints.add(HINT_DATE_FORMATS);
    }

    /** Copies the four ws23 extraction hints from {@code other}. */
    public void copyExtractionHints(TemplateFields other) {
        this.aliasesJson = other.aliasesJson;
        this.labelPattern = other.labelPattern;
        this.valuePattern = other.valuePattern;
        this.dateFormatsJson = other.dateFormatsJson;
    }

    /** Copies only the hints {@code update} named (an update body); the rest stay. */
    public void mergeExtractionHints(TemplateFields update) {
        if (update.providedHints.contains(HINT_ALIASES)) {
            this.aliasesJson = update.aliasesJson;
        }
        if (update.providedHints.contains(HINT_LABEL_PATTERN)) {
            this.labelPattern = update.labelPattern;
        }
        if (update.providedHints.contains(HINT_VALUE_PATTERN)) {
            this.valuePattern = update.valuePattern;
        }
        if (update.providedHints.contains(HINT_DATE_FORMATS)) {
            this.dateFormatsJson = update.dateFormatsJson;
        }
    }

    @JsonIgnore
    public boolean hasExtractionHints() {
        return aliasesJson != null || labelPattern != null || valuePattern != null
                || dateFormatsJson != null;
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = JSON.readValue(json, STRING_LIST);
            return values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        } catch (JsonProcessingException e) {
            // A hand-edited, unreadable value is "no hints", never a failed read.
            return List.of();
        }
    }

    private static String writeList(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> cleaned = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The list could not be stored.", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
