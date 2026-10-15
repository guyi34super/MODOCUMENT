package com.modocument.doccreation.dto;

import java.time.LocalDateTime;

/**
 * One entry of a template's version history (V291, same shape as before
 * ws31): {@code sectionSource} says how the version's regions were found
 * (CODE, CODE+AI or MANUAL); {@code regionCount} (and, for older clients,
 * {@code sectionCount}) is its number of regions, {@code editCount} its
 * number of template edits.
 */
public record BlueprintVersionDto(int version,
                                  String sectionSource,
                                  int sectionCount,
                                  String note,
                                  Integer createdByUserId,
                                  LocalDateTime createdDate,
                                  int documentCount,
                                  boolean current,
                                  int editCount,
                                  int regionCount) {
}
