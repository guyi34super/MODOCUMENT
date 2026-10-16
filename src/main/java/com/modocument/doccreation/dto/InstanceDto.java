package com.modocument.doccreation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.PageInfo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A document in the builder (contract §5): the template's pages and the
 * regions of the version it was started from (fractions, no sample values),
 * its values and photos, and the keys whose text will not fit their box at
 * the minimum font size ({@code overflow}: they continue on an added page).
 *
 * <p>ws32 (docs/doccreation/flow-records-contract.md §4):
 * {@code sourceRecords} — the records the document was filled from;
 * {@code canEditLayout} — the caller owns the template and holds
 * {@code doc:write}, so the builder may save a layout to it;
 * {@code blueprintEdits} — the template's current edits (the template
 * editor's shape) for the builder to resend unchanged with a layout save;
 * {@code droppedKeys} — only in the answer of {@code POST /upgrade}: the
 * region keys whose values (or photos) the new version no longer has.
 *
 * <p>ws33 (docs/doccreation/field-validation-contract.md §4.4):
 * {@code validation} — the last validation's result with the current state
 * ({@code checked} false while never validated); {@code props} — the field
 * properties of every region key of the document's version; {@code fonts} —
 * the bundled font names the builder's layout mode may pick (as
 * {@code GET /blueprints/{id}} returns them); {@code fieldChecks} — per
 * region key, the template's rules (with a plain summary), the built-in
 * checks and the data-source fields mapped onto it; {@code rulesRevision}
 * the template's rules revision; {@code canEditRules} — the caller owns the
 * template and holds {@code doc:write} (may open its Field rules tab).
 */
public record InstanceDto(int id,
                          String title,
                          String status,
                          int blueprintId,
                          String blueprintName,
                          int blueprintVersion,
                          int blueprintCurrentVersion,
                          List<PageInfo> pages,
                          List<RegionDto> regions,
                          Map<String, String> values,
                          List<ImageDto> images,
                          List<String> overflow,
                          LocalDateTime lastGeneratedDate,
                          boolean pdfAvailable,
                          String reviewerComment,
                          List<SourceRecordDto> sourceRecords,
                          boolean canEditLayout,
                          List<EditDto> blueprintEdits,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<String> droppedKeys,
                          ValidationResultDto validation,
                          Map<String, FieldProps> props,
                          List<String> fonts,
                          Map<String, FieldChecksDto> fieldChecks,
                          int rulesRevision,
                          boolean canEditRules) {

    /** A photo of the document: placed in {@code regionKey}, or in the tray when null. */
    public record ImageDto(int id, String regionKey, String originalName) {
    }

    /** A record the document was filled from (ids and the template's name only). */
    public record SourceRecordDto(long recordId, int templateId, String templateName, String role) {
    }

    /** This document with the keys an upgrade dropped. */
    public InstanceDto withDroppedKeys(List<String> keys) {
        return new InstanceDto(id, title, status, blueprintId, blueprintName, blueprintVersion,
                blueprintCurrentVersion, pages, regions, values, images, overflow, lastGeneratedDate, pdfAvailable,
                reviewerComment, sourceRecords, canEditLayout, blueprintEdits, List.copyOf(keys), validation, props, fonts,
                fieldChecks, rulesRevision, canEditRules);
    }
}
