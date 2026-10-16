package com.modocument.doccreation.fields;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Reads and writes {@link FieldProps} as the V320 {@code FieldPropsJson}
 * column (docs/doccreation/field-validation-contract.md §2). Lenient on
 * read: a null, blank or unreadable value is {@link FieldProps#NONE}, never
 * an error. An empty {@link FieldProps} is stored as null.
 */
public final class FieldPropsCodec {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FieldPropsCodec() {
    }

    /** The stored properties; {@link FieldProps#NONE} when none are set or the JSON is unreadable. */
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

    /** The column value: null for null or empty properties. */
    public static String write(FieldProps props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(props);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the field properties.", e);
        }
    }
}
