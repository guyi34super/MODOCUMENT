package com.modocument.infrastructure.prompt;

import com.modocument.application.dto.PromptInput;
import com.modocument.application.dto.TemplateIdentifierPromptInput;
import com.modocument.application.port.PromptStrategy;
import com.modocument.infrastructure.file.FileTextReader;
import org.springframework.stereotype.Component;

@Component
public class TemplateIdentifierPrompt implements PromptStrategy {

    private static final int MAX_SNIPPET_CHARS = 2000;

    private final FileTextReader fileTextReader;

    public TemplateIdentifierPrompt(FileTextReader fileTextReader) {
        this.fileTextReader = fileTextReader;
    }

    @Override
    public String getType() {
        return "TemplateIdentifier";
    }

    @Override
    public String buildPrompt(PromptInput input) {
        if (!(input instanceof TemplateIdentifierPromptInput templateIdentifier)) {
            throw new IllegalArgumentException("Invalid input type for TemplateIdentifierPrompt");
        }

        String employeeDetailsTemplate =
                "EmployeeDetails: contains columns like Name, LastName, FirstName, DateOfBirth, Address, HireDate";
        String ytdTemplate =
                "YTD: contains year-to-date journal entries, ranges of dates with values";
        String timeSheetTemplate =
                "Timesheet: contains earnings codes and hours worked per pay period";

        String snippet = fileTextReader.readSnippet(templateIdentifier.getFilePath(), MAX_SNIPPET_CHARS);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document-template classifier.").append(System.lineSeparator());
        prompt.append("Choose exactly one of the following templates based on the file snippet:")
                .append(System.lineSeparator());
        prompt.append("  - ").append(employeeDetailsTemplate).append(System.lineSeparator());
        prompt.append("  - ").append(ytdTemplate).append(System.lineSeparator());
        prompt.append("  - ").append(timeSheetTemplate).append(System.lineSeparator());
        prompt.append(System.lineSeparator());
        prompt.append("File snippet:").append(System.lineSeparator());
        prompt.append("```").append(System.lineSeparator());
        prompt.append(snippet).append(System.lineSeparator());
        prompt.append("```").append(System.lineSeparator());
        prompt.append(System.lineSeparator());
        prompt.append("Answer with exactly one mapped integer template "
                + "(EmployeeDetails as 1, YTD as 4 as , or Timesheet as 2).");

        return prompt.toString();
    }
}
