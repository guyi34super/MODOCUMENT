package com.modocument.infrastructure.rule;

import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

@Component
public class DateFormatRule implements ValidationRuleValidator {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    @Override
    public boolean canHandle(RuleType type) {
        return type == RuleType.DATE_FORMAT;
    }

    @Override
    public ValidationError validate(int row, String fieldName, String rawValue, String configJson) {
        try {
            LocalDate.parse(rawValue == null ? "" : rawValue, FORMAT);
        } catch (DateTimeParseException e) {
            return new ValidationError(row, fieldName, rawValue, "Date must be in the format YYYY-MM-DD");
        }
        return null;
    }
}
