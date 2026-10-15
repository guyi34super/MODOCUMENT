package com.modocument.doccreation.pdftemplate;

import java.util.List;

/**
 * Where a glyph or an image is drawn in the page content.
 *
 * @param formPath      indexes of the {@code Do} operators leading from the page content into nested Form
 *                      XObjects (empty for the page content itself)
 * @param opIndex       index of the operator in that stream (top-level operators only, the way
 *                      PDFStreamParser yields them)
 * @param stringOrdinal for text: index of the string within the operator (TJ arrays), else -1
 * @param glyphOrdinal  for text: index of the character code within that string, else -1
 * @param editable      false for content the editor must not rewrite (patterns, Type 3 glyph procedures)
 */
record ContentLocation(List<Integer> formPath, int opIndex, int stringOrdinal, int glyphOrdinal, boolean editable) {
}
