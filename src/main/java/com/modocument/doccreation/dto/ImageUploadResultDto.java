package com.modocument.doccreation.dto;

import java.util.List;

/**
 * Result of {@code POST /api/doccreation/instances/{id}/images} (contract
 * §5): the photos auto-filled into image regions, the ones left in the tray
 * and the files refused.
 */
public record ImageUploadResultDto(List<AssignedDto> assigned,
                                   List<Integer> unassigned,
                                   List<RejectedDto> rejected) {

    public record AssignedDto(int imageId, String regionKey) {
    }

    public record RejectedDto(String name, String reason) {
    }
}
