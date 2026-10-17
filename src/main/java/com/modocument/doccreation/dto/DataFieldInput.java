package com.modocument.doccreation.dto;

import com.modocument.doccreation.fields.FieldProps;

/**
 * One data field as the editor sends it ({@code FieldInput} of
 * docs/doccreation/data-templates-contract.md §2, {@code PUT
 * /data-templates/{id}/fields}). {@code piiKind} is a flow {@code PiiKind}
 * name; the field's sort order is its position in the list.
 */
public record DataFieldInput(String name,
                             String label,
                             String kind,
                             String piiKind,
                             FieldProps props,
                             String sourceFieldName) {
}
