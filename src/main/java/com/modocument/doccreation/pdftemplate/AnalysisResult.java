package com.modocument.doccreation.pdftemplate;

import java.util.List;

/** What the engine proposes for an uploaded PDF: regions to fill and, for WITH_DATA, the data to remove. */
public record AnalysisResult(
        int pageCount,
        List<PageInfo> pages,
        UploadFlow flowUsed,
        boolean hasAcroForm,
        boolean looksFilled,
        List<TemplateRegion> regions,
        List<TemplateEdit> removals,
        List<String> warnings,
        String detectedBy) {
}
