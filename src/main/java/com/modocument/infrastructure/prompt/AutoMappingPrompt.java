package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.AutoMappingPromptInput;
import com.modocument.application.dto.AutoMappingPromptInput.ColumnInfo;
import com.modocument.application.dto.AutoMappingPromptInput.FieldInfo;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AutoMappingPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "AutoMapping";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof AutoMappingPromptInput autoMapperInput)) {
            throw new IllegalArgumentException("Invalid input type for AutoMappingPrompt");
        }

        List<ColumnInfo> columns = autoMapperInput.getColumnsToMap();
        List<FieldInfo> fields = autoMapperInput.getTemplateFields();
        int columnCount = columns == null ? 0 : columns.size();
        int fieldCount = fields == null ? 0 : fields.size();
        int sampleLimit = Math.max(1, autoMapperInput.getSampleSize());

        String nl = System.lineSeparator();
        StringBuilder prompt = new StringBuilder();
        prompt.append("TASK: Map each SOURCE COLUMN to the best TEMPLATE FIELD.").append(nl);
        prompt.append("Return ONE JSON object only — keys = source column names, values = template field names or null.").append(nl);
        prompt.append(nl);
        prompt.append("STEPS:").append(nl);
        prompt.append("1. Read each source column name and its sample values.").append(nl);
        prompt.append("2. Pick the semantically best template field (abbreviations and synonyms OK).").append(nl);
        prompt.append("3. Each template field may be used at most once.").append(nl);
        prompt.append("4. Use null only when no field is a reasonable match.").append(nl);
        prompt.append(nl);

        String exampleColumn = columnCount > 0 ? columns.get(0).name() : "SourceCol";
        String exampleField = fields == null ? "TemplateField" : fields.stream()
                .filter(FieldInfo::available)
                .map(FieldInfo::name)
                .findFirst()
                .orElse(fields.isEmpty() ? "TemplateField" : fields.get(0).name());
        prompt.append("EXAMPLE: {\"").append(exampleColumn).append("\":\"")
                .append(exampleField).append("\",\"UnmappedCol\":null}").append(nl);
        prompt.append("JSON RULES: double-quoted keys/strings; no markdown; no trailing commas.").append(nl);
        prompt.append(nl);
        prompt.append("MATCHING HINTS:").append(nl);
        prompt.append("- \"Emp ID\", \"Employee ID\" → EmployeeNumber or ID").append(nl);
        prompt.append("- \"Full Name\", \"Name\" → Name or FullName").append(nl);
        prompt.append("- \"Dept\", \"Department\" → Department or Dept").append(nl);
        prompt.append("- \"Salary\", \"Pay\", \"Compensation\" → salary-related fields").append(nl);
        prompt.append("- Prefer type-compatible matches (dates→Date, numbers→Number).").append(nl);
        prompt.append("- Override \"Already mapped\" or \"Extraction suggestions\" if they look wrong.").append(nl);
        prompt.append(nl);

        appendConfirmedMappings(prompt, autoMapperInput.getConfirmedMappings(), nl);
        appendExtractionSuggestedMappings(prompt, autoMapperInput.getExtractionSuggestedMappings(), nl);
        appendColumnsToMap(prompt, columns, sampleLimit, nl);
        appendTemplateFields(prompt, fields, nl);
        appendAvailableFields(prompt, fields, nl);

        return prompt.toString();
    }

    private static void appendConfirmedMappings(StringBuilder prompt,
                                                Map<String, String> confirmed,
                                                String nl) {
        prompt.append("Already mapped (code-tier hints — may override if incorrect):").append(nl);
        if (confirmed == null || confirmed.isEmpty()) {
            prompt.append("(none)").append(nl);
        } else {
            confirmed.forEach((column, field) ->
                    prompt.append("- \"").append(column).append("\" → \"").append(field).append("\"").append(nl));
        }
        prompt.append(nl);
    }

    private static void appendExtractionSuggestedMappings(StringBuilder prompt,
                                                          Map<String, String> suggested,
                                                          String nl) {
        prompt.append("Extraction-time suggestions (verify and override if incorrect):").append(nl);
        if (suggested == null || suggested.isEmpty()) {
            prompt.append("(none)").append(nl);
        } else {
            suggested.forEach((column, field) ->
                    prompt.append("- \"").append(column).append("\" → ")
                            .append(field == null ? "null" : "\"" + field + "\"")
                            .append(nl));
        }
        prompt.append(nl);
    }

    private static void appendColumnsToMap(StringBuilder prompt, List<ColumnInfo> columns,
                                           int sampleLimit, String nl) {
        if (columns != null && !columns.isEmpty()) {
            int count = columns.size();
            prompt.append("map ").append(count).append(" source column")
                    .append(count == 1 ? "" : "s").append(nl);
        }
        prompt.append("Source columns to map:").append(nl);
        if (columns == null || columns.isEmpty()) {
            prompt.append("(none)").append(nl);
            return;
        }
        int index = 1;
        for (ColumnInfo column : columns) {
            prompt.append(index++).append(". \"").append(column.name()).append("\"");
            List<String> samples = column.sampleValues();
            if (samples != null && !samples.isEmpty()) {
                String joined = samples.stream()
                        .limit(sampleLimit)
                        .map(AutoMappingPrompt::quoteSample)
                        .collect(Collectors.joining(", "));
                prompt.append(" | samples: ").append(joined);
            }
            prompt.append(nl);
        }
        prompt.append(nl);
    }

    private static void appendTemplateFields(StringBuilder prompt, List<FieldInfo> fields, String nl) {
        prompt.append("Template fields (name | type | rules | status):").append(nl);
        if (fields == null || fields.isEmpty()) {
            prompt.append("(none)").append(nl);
            return;
        }
        int index = 1;
        for (FieldInfo field : fields) {
            prompt.append(index++).append(". \"").append(field.name()).append("\" | ")
                    .append(field.dataType() == null || field.dataType().isBlank() ? "Text" : field.dataType());
            if (field.ruleHint() != null && !field.ruleHint().isBlank()) {
                prompt.append(" | rules: ").append(field.ruleHint());
            }
            prompt.append(" | ").append(field.available() ? "available" : "TAKEN").append(nl);
        }
        prompt.append(nl);
    }

    private static void appendAvailableFields(StringBuilder prompt, List<FieldInfo> fields, String nl) {
        prompt.append("Available template fields (assign ONLY from this list):").append(nl);
        if (fields == null || fields.isEmpty()) {
            prompt.append("(none)").append(nl);
            return;
        }
        String available = fields.stream()
                .filter(FieldInfo::available)
                .map(FieldInfo::name)
                .collect(Collectors.joining(", "));
        prompt.append(available.isBlank() ? "(none)" : available).append(nl);
    }

    private static String quoteSample(String value) {
        if (value == null) {
            return "\"\"";
        }
        String trimmed = value.length() > 80 ? value.substring(0, 77) + "..." : value;
        return "\"" + trimmed.replace("\"", "'") + "\"";
    }
}
