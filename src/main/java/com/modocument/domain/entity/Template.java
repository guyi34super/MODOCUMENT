package com.modocument.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import com.modocument.domain.model.TemplateVisibility;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;

@Entity
// SCHEMA PARITY with V30 (uq_template_owneruserid_name): one template name
// per owner, enforced on H2 (ddl-auto: create) exactly like on PostgreSQL.
@Table(name = "Template", uniqueConstraints = @UniqueConstraint(
        name = "uq_template_owneruserid_name", columnNames = {"OwnerUserId", "Name"}))
// SCHEMA PARITY with V220 (ws22): Visibility NOT NULL DEFAULT 'PRIVATE'.
@Check(name = "ck_template_visibility", constraints = "Visibility in ('PRIVATE', 'BANK')")
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
     * PRIVATE (owner only, the default) or BANK (shared with the owner's
     * bank; changed only with template:publish) — V220, ws22. Set through
     * the visibility endpoint or CreateWithFields, never from a request
     * body of the legacy CRUD routes.
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PRIVATE'") // H2 parity: raw-SQL inserts land PRIVATE, as on PG
    @Column(name = "Visibility", length = 10, nullable = false)
    private TemplateVisibility visibility = TemplateVisibility.PRIVATE;

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

    public TemplateVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(TemplateVisibility visibility) {
        this.visibility = visibility == null ? TemplateVisibility.PRIVATE : visibility;
    }

    /** Bank-shared: readable and usable by every user of the template's bank. */
    @JsonIgnore
    public boolean isShared() {
        return visibility == TemplateVisibility.BANK;
    }
}
