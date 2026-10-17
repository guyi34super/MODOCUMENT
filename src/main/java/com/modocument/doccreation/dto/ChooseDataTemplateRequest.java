package com.modocument.doccreation.dto;

/**
 * {@code PUT /blueprints/{id}/data-template} (data-templates-contract §3):
 * the data template to use, or null to clear the choice (and the mapping).
 */
public record ChooseDataTemplateRequest(Integer dataTemplateId) {
}
