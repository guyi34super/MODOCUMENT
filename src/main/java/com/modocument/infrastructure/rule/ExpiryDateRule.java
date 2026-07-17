package com.modocument.infrastructure.rule;

import com.modocument.application.port.ValidationRuleValidator;
import com.modocument.domain.model.RuleType;
import com.modocument.domain.model.ValidationError;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * The value, when it parses as a date at all, must lie in the future.
 * Unparseable values pass (the DateFormat rule reports those).
 */
@Component
public class ExpiryDateRule implements ValidationRuleValidator {

    @Override
    public boolean canHandle(RuleType type) {
        return type == RuleType.EXPIRY_DATE;
    }

    @Override
    public ValidationError validate(int row, String fieldName, String rawValue, String configJson) {
        LocalDateTime value = tryParse(rawValue);
        if (value != null && !value.isAfter(LocalDateTime.now())) {
            return new ValidationError(row, fieldName, rawValue, "Expiry date must be in the future.");
        }
        return null;
    }

    private static LocalDateTime tryParse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String text = rawValue.trim();
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            // fall through to date-time parsing
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
