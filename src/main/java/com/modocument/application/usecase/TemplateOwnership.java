package com.modocument.application.usecase;

import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.TemplateRepository;
import com.modocument.application.security.RolePermissions;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Permission;
import com.modocument.domain.model.Role;
import com.modocument.domain.model.TemplateVisibility;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * The one authorization guard for templates and everything that hangs off
 * them (fields, validation rules, validation runs, and every place a
 * template id is USED — uploads, extraction, bridge, transform). Split in
 * ws22 when templates gained a visibility (docs/phase2/template-visibility.md
 * classifies every call site):
 *
 * <ul>
 *   <li>{@link #requireReadable} — read or USE: the caller owns the
 *       template, or it is {@link TemplateVisibility#BANK BANK}-shared within
 *       the caller's bank. Anything else is a {@link NoSuchElementException}
 *       (404): another user's private template and another bank's template
 *       are indistinguishable from a missing id.</li>
 *   <li>{@link #requireWritable} — change the template, its fields or its
 *       rules: a PRIVATE template only by its owner (anyone else got the 404
 *       already); a BANK template only by a holder of
 *       {@link Permission#TEMPLATE_PUBLISH} — even its owner, once it is
 *       shared — otherwise {@link AccessDeniedException} (403: the caller
 *       may see it, so hiding it would be pointless).</li>
 * </ul>
 */
@Service
public class TemplateOwnership {

    private final TemplateRepository templates;
    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;

    public TemplateOwnership(TemplateRepository templates, CurrentUserProvider currentUser,
                             AppUserRepository users) {
        this.templates = templates;
        this.currentUser = currentUser;
        this.users = users;
    }

    /**
     * Read / use: the caller's own template or a template shared with the
     * caller's bank.
     *
     * @throws NoSuchElementException (404) otherwise
     */
    public Template requireReadable(int templateId) {
        return templates.getAccessible(templateId, currentUser.requireUserId(),
                        currentUser.requireBankId())
                .orElseThrow(() -> notFound(templateId));
    }

    /**
     * Change: see the class comment.
     *
     * @throws NoSuchElementException (404) when the caller cannot even read it
     * @throws AccessDeniedException  (403) for a shared template without
     *                                template:publish
     */
    public Template requireWritable(int templateId) {
        Template template = requireReadable(templateId);
        requireWriteRight(template, currentUser.requireUserId(), currentRoleOrNull());
        return template;
    }

    /**
     * {@link #requireWritable} judged for {@code actingUserId} instead of the
     * caller — the maker of an approved TEMPLATE_UPDATE / TEMPLATE_DELETE,
     * re-checked at apply time (the checker approves the maker's change, they
     * do not lend the maker their rights; the maker's role may also have
     * changed since submitting). AppUsers is not under RLS, so the maker's
     * bank comes from their row, never from the caller.
     */
    public Template requireWritableBy(int templateId, int actingUserId) {
        if (actingUserId == currentUser.requireUserId()) {
            return requireWritable(templateId);
        }
        AppUser actor = users.getById(actingUserId).orElseThrow(() -> notFound(templateId));
        Template template = templates.getAccessible(templateId, actingUserId, actor.getBankId())
                .orElseThrow(() -> notFound(templateId));
        requireWriteRight(template, actingUserId, actor.getRole());
        return template;
    }

    /** Whether the caller may share/unshare templates (template:publish). */
    public boolean callerCanPublish() {
        return canPublish(currentRoleOrNull());
    }

    /** The caller, for stamping owned children. */
    public int requireUserId() {
        return currentUser.requireUserId();
    }

    /** The caller's bank, for stamping BankId on owned children. */
    public int requireBankId() {
        return currentUser.requireBankId();
    }

    private static void requireWriteRight(Template template, int userId, Role role) {
        if (template.getVisibility() == TemplateVisibility.BANK) {
            if (!canPublish(role)) {
                throw new AccessDeniedException("Template " + template.getTemplateId()
                        + " is shared with the bank: only an administrator (template:publish)"
                        + " can change it.");
            }
            return;
        }
        // PRIVATE: getAccessible only returns it to its owner; re-asserted so
        // a future widening of the read predicate can never widen writes.
        if (template.getOwnerUserId() != userId) {
            throw notFound(template.getTemplateId());
        }
    }

    private static boolean canPublish(Role role) {
        return role != null && RolePermissions.isGranted(role, Permission.TEMPLATE_PUBLISH);
    }

    private Role currentRoleOrNull() {
        try {
            return currentUser.role();
        } catch (IllegalStateException noRole) {
            return null;
        }
    }

    private static NoSuchElementException notFound(int templateId) {
        return new NoSuchElementException("Template " + templateId + " not found.");
    }
}
