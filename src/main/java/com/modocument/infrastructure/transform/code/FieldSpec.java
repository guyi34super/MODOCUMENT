package com.modocument.infrastructure.transform.code;

import com.modocument.domain.entity.TemplateDataType;
import com.modocument.domain.entity.TemplateFields;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One template field as the code extractor sees it: its name, data type and
 * the optional extraction hints a template author can give (aliases, a label
 * regex, a value regex with one capture group, accepted date formats).
 *
 * <p>The hints live on {@code TemplateFields} (V230): {@link #from(TemplateFields)}
 * carries them over; the {@code with*} methods override them.
 *
 * @param name         field name, also the output column name for label/regex strategies
 * @param type         TEXT, NUMBER or DATE (TemplateDataType ids 1, 2, 3)
 * @param aliases      alternative header/label texts (matched case-insensitively)
 * @param labelPattern optional regex for the field's label (vetted by SafeRegex)
 * @param valuePattern optional regex for the field's value; group 1 when present
 * @param dateFormats  optional {@code DateTimeFormatter} patterns tried before the built-ins
 */
public record FieldSpec(String name,
                        Type type,
                        List<String> aliases,
                        String labelPattern,
                        String valuePattern,
                        List<String> dateFormats) {

    /** Template data types the extractor understands; anything else is TEXT. */
    public enum Type {
        TEXT, NUMBER, DATE;

        /** Maps {@code TemplateDataTypes} (1 Text, 2 Number, 3 Date) by name, then by id. */
        public static Type of(TemplateDataType dataType, int dataTypeId) {
            String name = dataType == null ? null : dataType.getDataType();
            if (name != null && !name.isBlank()) {
                return switch (name.trim().toLowerCase(Locale.ROOT)) {
                    case "number" -> NUMBER;
                    case "date" -> DATE;
                    default -> TEXT;
                };
            }
            return switch (dataTypeId) {
                case 2 -> NUMBER;
                case 3 -> DATE;
                default -> TEXT;
            };
        }
    }

    public FieldSpec {
        Objects.requireNonNull(name, "name");
        type = type == null ? Type.TEXT : type;
        aliases = aliases == null ? List.of() : aliases.stream()
                .filter(a -> a != null && !a.isBlank()).map(String::trim).toList();
        labelPattern = labelPattern == null || labelPattern.isBlank() ? null : labelPattern;
        valuePattern = valuePattern == null || valuePattern.isBlank() ? null : valuePattern;
        dateFormats = dateFormats == null ? List.of() : dateFormats.stream()
                .filter(f -> f != null && !f.isBlank()).map(String::trim).toList();
    }

    public static FieldSpec of(String name, Type type) {
        return new FieldSpec(name, type, List.of(), null, null, List.of());
    }

    /** A template field: name, data type and its V230 extraction hints. */
    public static FieldSpec from(TemplateFields field) {
        return new FieldSpec(field.getFieldName(),
                Type.of(field.getTemplateDataType(), field.getTemplateDataTypeId()),
                field.getAliases(), field.getLabelPattern(), field.getValuePattern(),
                field.getDateFormats());
    }

    public FieldSpec withAliases(List<String> newAliases) {
        return new FieldSpec(name, type, newAliases, labelPattern, valuePattern, dateFormats);
    }

    public FieldSpec withLabelPattern(String pattern) {
        return new FieldSpec(name, type, aliases, pattern, valuePattern, dateFormats);
    }

    public FieldSpec withValuePattern(String pattern) {
        return new FieldSpec(name, type, aliases, labelPattern, pattern, dateFormats);
    }

    public FieldSpec withDateFormats(List<String> formats) {
        return new FieldSpec(name, type, aliases, labelPattern, valuePattern, formats);
    }

    /** A transient entity for {@code ValueShapeScorer}, which scores against {@code TemplateFields}. */
    TemplateFields toTemplateField() {
        TemplateFields field = new TemplateFields();
        field.setFieldName(name);
        int id = switch (type) {
            case TEXT -> 1;
            case NUMBER -> 2;
            case DATE -> 3;
        };
        field.setTemplateDataTypeId(id);
        String label = switch (type) {
            case TEXT -> "Text";
            case NUMBER -> "Number";
            case DATE -> "Date";
        };
        field.setTemplateDataType(new TemplateDataType(id, label));
        return field;
    }
}
