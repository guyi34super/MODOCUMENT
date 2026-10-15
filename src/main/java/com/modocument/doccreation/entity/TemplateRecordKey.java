package com.modocument.doccreation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * The normalised value of one field of one {@link TemplateRecord} (V310):
 * trimmed, lower-cased, whitespace and punctuation removed
 * (RecordText#key). Any field can join a LINKED source to its PRIMARY.
 */
@Entity
@Table(name = "TemplateRecordKey")
@IdClass(TemplateRecordKey.Pk.class)
public class TemplateRecordKey {

    /** The longest KeyValue stored (longer normalised values are cut). */
    public static final int MAX_KEY_LENGTH = 256;

    @Id
    @Column(name = "TemplateRecordId", nullable = false)
    private Long templateRecordId;

    @Id
    @Column(name = "FieldName", nullable = false, length = 255)
    private String fieldName;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "KeyValue", nullable = false, length = MAX_KEY_LENGTH)
    private String keyValue;

    public TemplateRecordKey() {
    }

    public TemplateRecordKey(Long templateRecordId, int bankId, String fieldName, String keyValue) {
        this.templateRecordId = templateRecordId;
        this.bankId = bankId;
        this.fieldName = fieldName;
        this.keyValue = keyValue;
    }

    public Long getTemplateRecordId() {
        return templateRecordId;
    }

    public String getFieldName() {
        return fieldName;
    }

    public int getBankId() {
        return bankId;
    }

    public String getKeyValue() {
        return keyValue;
    }

    /** The composite primary key (TemplateRecordId, FieldName). */
    public static class Pk implements Serializable {

        private Long templateRecordId;
        private String fieldName;

        public Pk() {
        }

        public Pk(Long templateRecordId, String fieldName) {
            this.templateRecordId = templateRecordId;
            this.fieldName = fieldName;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pk pk && Objects.equals(templateRecordId, pk.templateRecordId)
                    && Objects.equals(fieldName, pk.fieldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateRecordId, fieldName);
        }
    }
}
