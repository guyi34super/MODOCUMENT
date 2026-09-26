package com.modocument.doccreation.dto;

import java.time.LocalDateTime;

public record GenerationResultDto(String docxFileName,
                                  String pdfFileName,
                                  boolean pdfReady,
                                  String pdfError,
                                  LocalDateTime generatedDate,
                                  String composedBy) {
}
