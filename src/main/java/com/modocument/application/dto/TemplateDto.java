package com.modocument.application.dto;

import com.modocument.domain.entity.Template;

/**
 * A template as the caller sees it (GET /Templates/GetAll, GetById and the
 * visibility endpoint; ws22). The legacy fields are unchanged; ws22 adds:
 *
 * <ul>
 *   <li>{@code visibility} — PRIVATE or BANK (docs/phase2/contracts.md §4);</li>
 *   <li>{@code ownedByMe} — the caller owns it;</li>
 *   <li>{@code editable} — the caller may change it, its fields and rules
 *       (PRIVATE: the owner; BANK: template:publish holders) — the same rule
 *       the server enforces, so the UI can render a read-only view;</li>
 *   <li>{@code ownerName} — the owner's display name, for templates shared
 *       by a colleague (null for the caller's own templates).</li>
 * </ul>
 */
public record TemplateDto(int templateId, String name, String description, String visibility,
                          boolean ownedByMe, boolean editable, String ownerName) {

    public static TemplateDto of(Template template, boolean ownedByMe, boolean editable,
                                 String ownerName) {
        return new TemplateDto(template.getTemplateId(), template.getName(),
                template.getDescription(), template.getVisibility().name(), ownedByMe, editable,
                ownedByMe ? null : ownerName);
    }
}
