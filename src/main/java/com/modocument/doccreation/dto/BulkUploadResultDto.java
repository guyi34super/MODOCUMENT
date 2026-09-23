package com.modocument.doccreation.dto;

import java.util.List;

/** Outcome of a bulk image upload: what auto-assigned where, what waits in Unsorted, what was rejected. */
public record BulkUploadResultDto(List<AssignedDto> assigned,
                                  List<UnsortedDto> unsorted,
                                  List<RejectedDto> rejected) {

    public record AssignedDto(int assetId, String originalFileName, int sectionId, int blockId) {
    }

    public record UnsortedDto(int assetId, String originalFileName) {
    }

    public record RejectedDto(String fileName, String error) {
    }
}
