package com.modocument.doccreation.dto;

import java.time.LocalDateTime;

/** One entry of a blueprint's version history (V291). */
public record BlueprintVersionDto(int version,
                                  String sectionSource,
                                  int sectionCount,
                                  String note,
                                  Integer createdByUserId,
                                  LocalDateTime createdDate,
                                  int documentCount,
                                  boolean current) {
}
