package com.modocument.doccreation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateInstanceFieldsRequest {

    @NotNull
    @Valid
    private List<FieldValueInput> fields;

    public List<FieldValueInput> getFields() {
        return fields;
    }

    public void setFields(List<FieldValueInput> fields) {
        this.fields = fields;
    }

    public static class FieldValueInput {

        @NotNull
        private Integer sectionId;

        @NotBlank
        @Size(max = 64)
        private String fieldKey;

        @Size(max = 4000)
        private String value;

        public Integer getSectionId() {
            return sectionId;
        }

        public void setSectionId(Integer sectionId) {
            this.sectionId = sectionId;
        }

        public String getFieldKey() {
            return fieldKey;
        }

        public void setFieldKey(String fieldKey) {
            this.fieldKey = fieldKey;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
