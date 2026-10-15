package com.modocument.doccreation.pdftemplate;

import java.util.List;

public record FillResult(byte[] pdf, List<String> continuedKeys, int addedPages, ExactnessReport report) {
}
