package com.modocument.doccreation.rules;

import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.RegionKind;

/** The parts of a template field the checks need (field-validation-contract §6). */
public record RegionView(String key, String label, RegionKind kind, FieldProps props) {

    public RegionView {
        props = props == null ? FieldProps.NONE : props;
        label = label == null || label.isBlank() ? key : label;
    }
}
