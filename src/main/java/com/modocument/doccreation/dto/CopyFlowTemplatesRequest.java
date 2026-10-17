package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code POST /api/doccreation/data-templates/from-flow-templates}
 * (docs/doccreation/data-templates-contract.md §2, "Building from several
 * flow templates"): a new data template named {@code name} from the chosen
 * fields of several flow templates, merged in the order of {@code sources}.
 */
public record CopyFlowTemplatesRequest(String name, List<Source> sources) {

    /**
     * One flow template and the flow field names to copy from it, in any
     * order; null copies every field, an empty list is refused (400).
     */
    public record Source(Integer templateId, List<String> fields) {
    }
}
