package com.modocument.doccreation.dto;

/** A run of body elements not covered by any template section (V292 template review). */
public record BlueprintGapDto(int fromIndex, int toIndex, String preview) {
}
