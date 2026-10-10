package com.modocument.infrastructure.transform.code;

import com.modocument.application.dto.TableExtractionHints;
import com.modocument.domain.entity.ValidationRule;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the code extractor reads for one document.
 *
 * @param text          plain text as the parse stage extracted it (may be empty when only the file is usable)
 * @param file          the uploaded file, when available (native sheets, raw CSV bytes, PDF layout)
 * @param fileType      lower-cased extension with the dot ({@code .csv}); derived from {@code file} when null
 * @param fields        the template's fields with their extraction hints; may be empty
 * @param hints         the parse stage's table pre-scan, used for row-count agreement; may be null
 * @param globalAliases {@code mapping.aliases}: normalized token to canonical token
 * @param rulesByField  the template's validation rules by field name, for value-shape scoring; may be empty
 */
public record ExtractionInput(String text,
                              Path file,
                              String fileType,
                              List<FieldSpec> fields,
                              TableExtractionHints hints,
                              Map<String, String> globalAliases,
                              Map<String, List<ValidationRule>> rulesByField) {

    public ExtractionInput {
        text = text == null ? "" : text;
        if ((fileType == null || fileType.isBlank()) && file != null) {
            fileType = extensionOf(file);
        }
        fileType = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        fields = fields == null ? List.of() : List.copyOf(fields);
        globalAliases = globalAliases == null ? Map.of() : Map.copyOf(globalAliases);
        rulesByField = rulesByField == null ? Map.of() : Map.copyOf(rulesByField);
    }

    public static Builder builder() {
        return new Builder();
    }

    boolean hasFields() {
        return !fields.isEmpty();
    }

    static String extensionOf(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String value = name.toString();
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Fluent construction; every part is optional except some text or a file. */
    public static final class Builder {

        private String text;
        private Path file;
        private String fileType;
        private final List<FieldSpec> fields = new ArrayList<>();
        private TableExtractionHints hints;
        private final Map<String, String> globalAliases = new LinkedHashMap<>();
        private final Map<String, List<ValidationRule>> rulesByField = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder text(String value) {
            this.text = value;
            return this;
        }

        public Builder file(Path value) {
            this.file = value;
            return this;
        }

        public Builder fileType(String value) {
            this.fileType = value;
            return this;
        }

        public Builder fields(List<FieldSpec> value) {
            if (value != null) {
                this.fields.addAll(value);
            }
            return this;
        }

        public Builder field(FieldSpec value) {
            this.fields.add(value);
            return this;
        }

        public Builder hints(TableExtractionHints value) {
            this.hints = value;
            return this;
        }

        public Builder globalAliases(Map<String, String> value) {
            if (value != null) {
                this.globalAliases.putAll(value);
            }
            return this;
        }

        public Builder rulesByField(Map<String, List<ValidationRule>> value) {
            if (value != null) {
                this.rulesByField.putAll(value);
            }
            return this;
        }

        public ExtractionInput build() {
            return new ExtractionInput(text, file, fileType, fields, hints, globalAliases, rulesByField);
        }
    }
}
