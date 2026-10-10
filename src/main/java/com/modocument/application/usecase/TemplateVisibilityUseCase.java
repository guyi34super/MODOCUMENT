package com.modocument.application.usecase;

import com.modocument.application.dto.TemplateDto;
import com.modocument.application.port.AppUserRepository;
import com.modocument.application.port.AuditRecorder;
import com.modocument.application.port.CurrentUserProvider;
import com.modocument.application.port.IngestionJobRepository;
import com.modocument.application.port.TemplateRepository;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.TemplateVisibility;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Bank-shared templates (ws22, docs/phase2/template-visibility.md): the
 * caller's view of the templates they can see — their own plus those shared
 * with their bank — and the visibility switch itself.
 *
 * <p>Sharing is a template:publish (ADMIN) decision and applies directly
 * (audited TEMPLATE_VISIBILITY_CHANGED); the content of a shared template is
 * still changed through the maker/checker TEMPLATE_UPDATE / TEMPLATE_DELETE
 * flow, now open to template:publish holders.
 */
@Service
public class TemplateVisibilityUseCase {

    private final TemplateRepository templates;
    private final TemplateOwnership ownership;
    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final IngestionJobRepository jobs;
    private final AuditRecorder audit;

    public TemplateVisibilityUseCase(TemplateRepository templates, TemplateOwnership ownership,
                                     CurrentUserProvider currentUser, AppUserRepository users,
                                     IngestionJobRepository jobs, AuditRecorder audit) {
        this.templates = templates;
        this.ownership = ownership;
        this.currentUser = currentUser;
        this.users = users;
        this.jobs = jobs;
        this.audit = audit;
    }

    /** Own templates plus every template shared with the caller's bank, oldest first. */
    @Transactional(readOnly = true)
    public List<TemplateDto> list() {
        int userId = currentUser.requireUserId();
        int bankId = currentUser.requireBankId();
        List<Template> visible = templates.listAccessible(userId, bankId);
        Map<Integer, String> ownerNames = ownerNames(visible, userId, bankId);
        boolean canPublish = ownership.callerCanPublish();
        return visible.stream()
                .map(template -> view(template, userId, canPublish, ownerNames))
                .toList();
    }

    /** One readable template (404 otherwise). */
    @Transactional(readOnly = true)
    public TemplateDto get(int templateId) {
        Template template = ownership.requireReadable(templateId);
        int userId = currentUser.requireUserId();
        return view(template, userId, ownership.callerCanPublish(),
                ownerNames(List.of(template), userId, currentUser.requireBankId()));
    }

    /**
     * PUT /Templates/{id}/Visibility (template:publish). Sharing makes the
     * template readable and usable by every user of its bank; unsharing makes
     * it the owner's alone again — refused (409) while colleagues still have
     * unfinished upload jobs on it, which would otherwise lose access to its
     * fields mid-run. Setting the current value again is a no-op.
     *
     * @throws NoSuchElementException (404) not readable by the caller
     * @throws AccessDeniedException  (403) via the write check (defensive: the
     *                                route already demands template:publish)
     * @throws IllegalStateException  (409) unsharing a template in active use
     */
    @Transactional
    public TemplateDto changeVisibility(int templateId, TemplateVisibility target) {
        if (target == null) {
            throw new IllegalArgumentException("visibility is required: PRIVATE or BANK.");
        }
        Template template = ownership.requireWritable(templateId);
        TemplateVisibility before = template.getVisibility();
        if (before != target) {
            if (target == TemplateVisibility.PRIVATE) {
                int inUse = jobs.countActiveByTemplateIdOfOtherUsers(templateId,
                        template.getOwnerUserId());
                if (inUse > 0) {
                    throw new IllegalStateException("This shared template is in use by " + inUse
                            + (inUse == 1 ? " unfinished upload job" : " unfinished upload jobs")
                            + " of other users. Let them finish (or cancel them) before making"
                            + " it private.");
                }
            }
            template.setVisibility(target);
            template = templates.update(template);
            audit.success("TEMPLATE_VISIBILITY_CHANGED", "Template", String.valueOf(templateId),
                    Map.of("visibility", before.name()), Map.of("visibility", target.name()));
        }
        int userId = currentUser.requireUserId();
        return view(template, userId, ownership.callerCanPublish(),
                ownerNames(List.of(template), userId, currentUser.requireBankId()));
    }

    private static TemplateDto view(Template template, int userId, boolean canPublish,
                                    Map<Integer, String> ownerNames) {
        boolean mine = template.getOwnerUserId() == userId;
        boolean editable = template.isShared() ? canPublish : mine;
        return TemplateDto.of(template, mine, editable, ownerNames.get(template.getOwnerUserId()));
    }

    /**
     * Display names of the colleagues owning shared templates. AppUsers is
     * not under RLS: only the caller's own bank is listed, so a name can
     * never come from another bank.
     */
    private Map<Integer, String> ownerNames(List<Template> visible, int userId, int bankId) {
        boolean anyForeign = visible.stream().anyMatch(t -> t.getOwnerUserId() != userId);
        Map<Integer, String> names = new HashMap<>();
        if (!anyForeign) {
            return names;
        }
        for (AppUser user : users.listByBank(bankId)) {
            String name = user.getDisplayName();
            if (name != null && !name.isBlank()) {
                names.put(user.getAppUserId(), name);
            }
        }
        return names;
    }
}
