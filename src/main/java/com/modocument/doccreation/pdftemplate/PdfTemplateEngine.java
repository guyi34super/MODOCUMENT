package com.modocument.doccreation.pdftemplate;

import java.util.List;
import java.util.Map;

/**
 * Exact PDF templates: the template is the original PDF (data removed in the WITH_DATA flow) plus an
 * overlay of regions; generation draws values onto it. Contract: docs/doccreation/pdf-templates-contract.md §3.
 */
public interface PdfTemplateEngine {

    /** Throws {@link PdfTemplateException} for ENCRYPTED, TOO_MANY_PAGES (over 500), NOT_A_PDF, UNREADABLE. */
    AnalysisResult analyze(byte[] pdf, UploadFlow flow, AnalysisOptions options);

    /** Applies the edits (and clears AcroForm values when asked) and returns the template PDF. Deterministic. */
    byte[] applyEdits(byte[] originalPdf, List<TemplateEdit> edits, Map<String, byte[]> images, boolean clearAcroForm);

    /** Writes values and images into the regions; fills and flattens AcroForm fields. */
    FillResult fill(byte[] templatePdf, List<TemplateRegion> regions, Map<String, String> values,
                    Map<String, byte[]> images, FillOptions options);

    List<PageInfo> pages(byte[] pdf);

    /** PNG of one page at dpi (at most 200), rotated for display. */
    byte[] renderPage(byte[] pdf, int page, int dpi);

    /** Pixel comparison of every page outside the allowed areas (PDF points). */
    ExactnessReport verify(byte[] base, byte[] candidate, List<PdfBox> allowedAreas, int dpi);
}
