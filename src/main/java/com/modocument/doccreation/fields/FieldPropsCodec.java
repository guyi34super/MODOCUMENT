package com.modocument.doccreation.fields;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Reads and writes {@code DocTemplateRegion.FieldPropsJson}
 * (docs/doccreation/field-validation-contract.md §2). Reading never fails:
 * a null, blank or unreadable column is {@link FieldProps#NONE}.
 */
public final class FieldPropsCodec {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private FieldPropsCodec() {
    }

    /** The stored properties, {@link FieldProps#NONE} when none are set or the JSON is unreadable. */
    public static FieldProps read(String json) {
        if (json == null || json.isBlank()) {
            return FieldProps.NONE;
        }
        try {
            FieldProps props = JSON.readValue(json, FieldProps.class);
            return props == null ? FieldProps.NONE : props;
        } catch (IOException | RuntimeException e) {
            return FieldProps.NONE;
        }
    }

    /** The JSON to store: null for no properties (an empty {@link FieldProps} is stored as null). */
    public static String write(FieldProps props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(props);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the field's properties.", e);
        }
    }
}
