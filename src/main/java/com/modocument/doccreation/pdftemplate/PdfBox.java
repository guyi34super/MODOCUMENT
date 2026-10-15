package com.modocument.doccreation.pdftemplate;

/** A box in PDF points: 0-based page, unrotated cropBox user space, origin at the cropBox lower-left (docs/doccreation/pdf-templates-contract.md §2). */
public record PdfBox(int page, float x, float y, float w, float h) {
}
