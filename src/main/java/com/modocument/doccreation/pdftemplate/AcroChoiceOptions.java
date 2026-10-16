package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The options of a PDF's AcroForm dropdowns and list boxes
 * ({@code PDComboBox}/{@code PDListBox}), by fully qualified field name —
 * the {@code acroFieldName} of the CHOICE regions the analysis proposes
 * (field-validation-contract §2). The display texts are returned (what a
 * user sees), trimmed, blank and duplicate entries left out. Kept outside
 * {@link AnalysisResult} on purpose: the engine contract stays unchanged
 * and the template use case reads the options where it stores the regions.
 */
public final class AcroChoiceOptions {

    /** At most this many options per field (FieldProps limit). */
    public static final int MAX_OPTIONS = 200;
    /** At most this many characters per option (FieldProps limit). */
    public static final int MAX_OPTION_LENGTH = 200;

    private AcroChoiceOptions() {
    }

    /** Field name to its options; empty when the PDF has no choice fields or cannot be read. */
    public static Map<String, List<String>> read(byte[] pdf) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        PDDocument doc = null;
        try {
            doc = PdfDocuments.load(pdf);
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            if (form == null) {
                return out;
            }
            for (PDField field : form.getFieldTree()) {
                if (field instanceof PDChoice choice) {
                    List<String> options = options(choice);
                    if (!options.isEmpty()) {
                        out.put(field.getFullyQualifiedName(), options);
                    }
                }
            }
        } catch (RuntimeException e) {
            return out;
        } finally {
            PdfDocuments.closeQuietly(doc);
        }
        return out;
    }

    /** The display texts of one choice field (export values when no display texts are set). */
    static List<String> options(PDChoice choice) {
        List<String> raw;
        try {
            raw = choice.getOptionsDisplayValues();
            if (raw == null || raw.isEmpty()) {
                raw = choice.getOptions();
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String option : raw == null ? List.<String>of() : raw) {
            if (option == null) {
                continue;
            }
            String clean = option.strip();
            if (clean.isEmpty() || clean.length() > MAX_OPTION_LENGTH) {
                continue;
            }
            seen.add(clean);
            if (seen.size() >= MAX_OPTIONS) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }
}
