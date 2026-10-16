package com.modocument.doccreation.pdftemplate;

import java.util.List;

/** The bundled Liberation fonts a region may name (field-validation-contract §2, {@code fonts}). */
public final class BundledFonts {

    private BundledFonts() {
    }

    /** The bundled font names, in a stable order. */
    public static List<String> names() {
        return FontResolver.BUNDLED;
    }
}
