package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.ExtractionPromptInput;
import com.modocument.application.dto.PromptInput;
import com.modocument.application.port.PromptStrategy;
import org.springframework.stereotype.Component;

@Component
public class ExtractionPrompt implements PromptStrategy {

    @Override
    public String getType() {
        return "Extraction";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof ExtractionPromptInput extractionInput)) {
            throw new IllegalArgumentException("Invalid input type for ExtractionPrompt");
        }

        StringBuilder sb = new StringBuilder();
        appendLine(sb, "You are a Data-Extraction Assistant.");
        appendLine(sb, "You will receive a raw text snippet containing field names and values in any format (CSV, PDF-text, key-value lines, etc.).");
        appendLine(sb, "");
        appendLine(sb, "Your job is to:");
        appendLine(sb, "1. Identify every field name and its associated value in the snippet.");
        appendLine(sb, "2. Normalize:");
        appendLine(sb, "   • Dates → ISO 8601 (YYYY-MM-DD)");
        appendLine(sb, "   • Numbers → plain numeric strings (no symbols or commas)");
        appendLine(sb, "3. Discard any fields not in the canonical list.");
        appendLine(sb, "4. **Output only** a single JSON object (no markdown, no commentary) where each property is the canonical name and its value is the extracted data (string, number, or null).");
        appendLine(sb, "");
        appendLine(sb, "Here is a model that you need to use to extract the data based upon:");
        appendLine(sb, extractionInput.getModel());
        appendLine(sb, "**Example**");
        appendLine(sb, "Input snippet:");
        appendLine(sb, "Employee Name: Stephen");
        appendLine(sb, "Middle Name: Lyn Nicole");
        appendLine(sb, "Last Name: Curie");
        appendLine(sb, "Birth Date: 11-07-2002");
        appendLine(sb, "Address: 8775 McNab Cres, Regina, SK S4S 4A9");
        appendLine(sb, "Hourly Rate: $30.50");
        appendLine(sb, "Vacation Annual Hours: 120");
        appendLine(sb, "");
        appendLine(sb, "Expected output: JSON such as:");
        appendLine(sb, "```json");
        appendLine(sb, "[");
        appendLine(sb, "  {");
        appendLine(sb, "    \"firstName\": \"Stephen\",");
        appendLine(sb, "    \"middleName\": \"Lyn Nicole\",");
        appendLine(sb, "    \"lastName\": \"Curie\",");
        appendLine(sb, "    \"birthDate\": \"2002-07-11\",");
        appendLine(sb, "    \"address\": \"8775 McNab Cres, Regina, SK S4S 4A9\",");
        appendLine(sb, "    \"hourlyRate\": \"30.50\",");
        appendLine(sb, "    \"gross pay\": \"120\",");
        appendLine(sb, "    \"EL wages\": \"120\"");
        appendLine(sb, "  }");
        appendLine(sb, "]");
        appendLine(sb, "```");
        appendLine(sb, "");
        appendLine(sb, "I’ve attached an Excel/CSV/PDF content below:");
        appendLine(sb, "```excelfile");
        appendLine(sb, extractionInput.getFileContent());
        appendLine(sb, "```");

        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String line) {
        sb.append(line).append(System.lineSeparator());
    }
}
