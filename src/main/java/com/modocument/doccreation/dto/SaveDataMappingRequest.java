package com.modocument.doccreation.dto;

import java.util.Map;

/**
 * {@code PUT /blueprints/{id}/data-mapping} (data-templates-contract §3):
 * region key to data field name; a blank or null field leaves the region
 * unmapped. A field may be mapped to several regions.
 */
public record SaveDataMappingRequest(Map<String, String> mapping) {
}
