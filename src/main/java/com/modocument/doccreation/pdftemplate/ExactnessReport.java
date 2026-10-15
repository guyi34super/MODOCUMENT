package com.modocument.doccreation.pdftemplate;

import java.util.List;

/** Result of a pixel comparison outside the allowed areas. */
public record ExactnessReport(boolean exact, long differingPixels, List<Integer> pagesWithDiffs, int dpi) {
}
