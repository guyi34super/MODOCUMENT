package com.modocument.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;

@Entity
// SCHEMA PARITY with V30 (uq_template_owneruserid_name): one template name
// per owner, enforced on H2 (ddl-auto: create) exactly like on PostgreSQL.
@Table(name = "Template", uniqueConstraints = @UniqueConstraint(
        name = "uq_template_owneruserid_name", columnNames = {"OwnerUserId", "Name"}))
// SCHEMA PARITY with V230 (ck_template_extractionmode) on the H2 test schema.
@Check(name = "ck_template_extractionmode",
        constraints = "ExtractionMode in ('AI_WITH_CODE_FALLBACK', 'CODE_ONLY', 'AI_ONLY')")
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "TemplateId")
    private int templateId;

    /** The AppUser who owns this template — every read/write is scoped to it. */
    @Column(name = "OwnerUserId")
    private int ownerUserId;

    /** The owner's bank (V11), stamped on create; RLS substrate. */
    @Column(name = "BankId")
    private int bankId;

    /** Doubles as the request schema cap when the entity arrives as a body. */
    @Size(max = 255)
    @Column(name = "Name")
    private String name;

    @Size(max = 255)
    @Column(name = "Description")
    private String description;

    /**
     * ws23: how the parse stage may use the AI for this template's uploads —
     * an {@code ExtractionMode} name, or null to inherit the bank's
     * {@code extraction.defaultMode} setting (V230).
     */
    @Size(max = 30)
    @Column(name = "ExtractionMode", length = 30)
    private String extractionMode;

    /**
     * Whether a request body named {@code extractionMode} at all (Jackson
     * calls the setter only for present properties): an update that omits it
     * keeps the stored mode, an explicit null resets it to "inherit".
     */
    @Transient
    private boolean extractionModeProvided;

    public Template() {
    }

    public Template(int templateId, String name, String description) {
        this.templateId = templateId;
        this.name = name;
        this.description = description;
    }

    public int getTemplateId() {
        return templateId;
    }

    public void setTemplateId(int templateId) {
        this.templateId = templateId;
    }

    @JsonIgnore
    public int getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(int ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    @JsonIgnore
    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExtractionMode() {
        return extractionMode;
    }

    public void setExtractionMode(String extractionMode) {
        this.extractionMode = extractionMode;
        this.extractionModeProvided = true;
    }

    @JsonIgnore
    public boolean isExtractionModeProvided() {
        return extractionModeProvided;
    }
}
