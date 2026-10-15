package com.modocument.doccreation.dto;

/**
 * One non-empty paragraph of the stored blueprint, for the section editor's
 * "add a section at this paragraph" picker. {@code index} is the body-element
 * index sections anchor to.
 */
public record BlueprintParagraphDto(int index, String text, String style, boolean headingCandidate) {
}
