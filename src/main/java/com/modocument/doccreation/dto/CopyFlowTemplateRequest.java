package com.modocument.doccreation.dto;

import java.util.List;

/**
 * {@code POST /api/doccreation/data-templates/from-flow-template}
 * (docs/doccreation/data-templates-contract.md §2): a new data template
 * named {@code name} from the flow template's fields listed in
 * {@code fields} (flow field names, in any order; null copies every field).
 */
public record CopyFlowTemplateRequest(Integer templateId, String name, List<String> fields) {
}
