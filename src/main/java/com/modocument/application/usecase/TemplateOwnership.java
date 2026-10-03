package com.modocument.application.usecase;

import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Shared authorization guard for template-scoped resources (fields, validation
 * rules, validation runs). A field or rule belongs to whoever owns its template,
 * so every one of those endpoints resolves ownership here — one place, so the
 * check can't be forgotten on a new endpoint and a later switch to
 * company-scoping is a single edit.
 */
@Service
public class TemplateOwnership {

    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;

    public TemplateOwnership(TemplateRepository templates, CurrentUserProvider currentUser) {
        this.templates = templates;
        this.currentUser = currentUser;
    }

    /**
     * Verifies the current user owns {@code templateId}.
     *
     * @return the caller's user id, for convenience when stamping owned children
     * @throws NoSuchElementException when the template does not exist or is not
     *     the caller's — surfaced as 404 so cross-user probing can't tell the two
     *     apart.
     */
    public int require(int templateId) {
        int userId = currentUser.requireUserId();
        templates.getByIdAndOwner(templateId, userId).orElseThrow(
                () -> new NoSuchElementException("Template " + templateId + " not found."));
        return userId;
    }

    /** The caller's bank, for stamping BankId on owned children. */
    public int requireBankId() {
        return currentUser.requireBankId();
    }
}
