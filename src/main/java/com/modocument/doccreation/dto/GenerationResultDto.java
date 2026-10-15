package com.modocument.doccreation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of {@code POST /api/doccreation/instances/{id}/generate} (contract
 * §5): the PDF is ready, {@code addedPages} continuation pages carry the
 * text of {@code continuedKeys}, and the exactness self-check passed.
 */
public record GenerationResultDto(boolean pdfReady,
                                  int addedPages,
                                  List<String> continuedKeys,
                                  boolean exact,
                                  LocalDateTime generatedDate) {
}
