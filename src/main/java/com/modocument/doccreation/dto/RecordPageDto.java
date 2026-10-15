package com.modocument.doccreation.dto;

import java.util.List;

/** One page of a record search (contract §4): {@code {items, page, size, total}}. */
public record RecordPageDto(List<RecordDto> items, int page, int size, long total) {
}
