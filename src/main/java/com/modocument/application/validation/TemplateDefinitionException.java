package com.modocument.application.validation;

import com.modocument.application.dto.TemplateValidationError;

import java.util.List;

/** Raised when a submitted template definition is invalid; carries all errors found. */
public class TemplateDefinitionException extends RuntimeException {

    private final List<TemplateValidationError> errors;

    public TemplateDefinitionException(List<TemplateValidationError> errors) {
        super("Template definition is invalid: " + errors.size() + " error(s).");
        this.errors = List.copyOf(errors);
    }

    public List<TemplateValidationError> getErrors() {
        return errors;
    }
}
