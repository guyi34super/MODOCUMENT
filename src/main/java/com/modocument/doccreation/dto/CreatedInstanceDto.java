package com.modocument.doccreation.dto;

/** Result of {@code POST /api/doccreation/instances}: the new document's id (twice, same value). */
public record CreatedInstanceDto(int id, int instanceId) {
}
