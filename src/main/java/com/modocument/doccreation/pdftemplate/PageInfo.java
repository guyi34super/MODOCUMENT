package com.modocument.doccreation.pdftemplate;

/** One page: cropBox size in points and its /Rotate (0, 90, 180, 270). */
public record PageInfo(int index, float width, float height, int rotation) {
}
