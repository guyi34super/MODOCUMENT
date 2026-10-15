package com.modocument.doccreation.usecase;

import com.modocument.doccreation.dto.EditDto;
import com.modocument.doccreation.dto.RegionDto;
import com.modocument.doccreation.entity.DocTemplateEdit;
import com.modocument.doccreation.entity.DocTemplateRegion;
import com.modocument.doccreation.pdftemplate.Align;
import com.modocument.doccreation.pdftemplate.EditKind;
import com.modocument.doccreation.pdftemplate.EditSource;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.RegionSource;
import com.modocument.doccreation.pdftemplate.TemplateEdit;
import com.modocument.doccreation.pdftemplate.TemplateRegion;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Converts the overlay between its three shapes: the engine records
 * ({@link TemplateRegion}/{@link TemplateEdit}, boxes in PDF points), the
 * stored rows and the API DTOs (boxes as displayed-page fractions through
 * {@link PageFractions}). Input from the editor is VALIDATED here (400 on
 * bad geometry, unknown kinds, invalid or duplicate keys); proposals from
 * the engine are NORMALIZED instead (a bad key is replaced, never refused).
 */
public final class OverlayMapper {

    public static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern EDIT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final Pattern IMAGE_KEY = Pattern.compile("img_[a-f0-9]{8,64}");
    private static final int MAX_LABEL = 200;
    private static final int MAX_GROUP = 120;
    private static final int MAX_TEXT = 2000;
    private static final float MAX_FONT_SIZE = 144f;
    private static final String DEFAULT_COLOR = "#000000";

    private OverlayMapper() {
    }

    // ---------------------------------------------------------- rows ↔ engine

    public static TemplateRegion toRegion(DocTemplateRegion row) {
        return new TemplateRegion(row.getRegionKey(), row.getLabel(), row.getGroupName(),
                RegionKind.valueOf(row.getKind()),
                new PdfBox(row.getPage(), (float) row.getX(), (float) row.getY(), (float) row.getW(),
                        (float) row.getH()),
                row.getFontName(), (float) row.getFontSize(), row.getColor(), Align.valueOf(row.getAlign()),
                row.getMaxLines(), row.getAcroFieldName(), row.isRequired(), row.getSortOrder(),
                RegionSource.valueOf(row.getSource()), row.getConfidence(), null);
    }

    public static DocTemplateRegion toRow(TemplateRegion region, int bankId, int blueprintId, int version) {
        DocTemplateRegion row = new DocTemplateRegion();
        row.setBankId(bankId);
        row.setBlueprintId(blueprintId);
        row.setVersion(version);
        row.setRegionKey(region.key());
        row.setLabel(region.label());
        row.setGroupName(region.group());
        row.setKind(region.kind().name());
        row.setPage(region.box().page());
        row.setX(region.box().x());
        row.setY(region.box().y());
        row.setW(region.box().w());
        row.setH(region.box().h());
        row.setFontName(region.fontName());
        row.setFontSize(region.fontSize());
        row.setColor(region.color());
        row.setAlign(region.align().name());
        row.setMaxLines(region.maxLines());
        row.setAcroFieldName(region.acroFieldName());
        row.setRequired(region.required());
        row.setSortOrder(region.sortOrder());
        row.setSource(region.source().name());
        row.setConfidence(region.confidence());
        return row;
    }

    public static TemplateEdit toEdit(DocTemplateEdit row) {
        return new TemplateEdit(row.getEditId(), EditKind.valueOf(row.getKind()),
                new PdfBox(row.getPage(), (float) row.getX(), (float) row.getY(), (float) row.getW(),
                        (float) row.getH()),
                row.getText(), row.getFontName(),
                row.getFontSize() == null ? null : row.getFontSize().floatValue(),
                row.getColor(), row.getImageKey(), EditSource.valueOf(row.getSource()), 1.0, null);
    }

    public static DocTemplateEdit toRow(TemplateEdit edit, int bankId, int blueprintId, int version) {
        DocTemplateEdit row = new DocTemplateEdit();
        row.setBankId(bankId);
        row.setBlueprintId(blueprintId);
        row.setVersion(version);
        row.setEditId(edit.id());
        row.setKind(edit.kind().name());
        row.setPage(edit.box().page());
        row.setX(edit.box().x());
        row.setY(edit.box().y());
        row.setW(edit.box().w());
        row.setH(edit.box().h());
        row.setText(edit.text());
        row.setFontName(edit.fontName());
        row.setFontSize(edit.fontSize() == null ? null : edit.fontSize().doubleValue());
        row.setColor(edit.color());
        row.setImageKey(edit.imageKey());
        row.setSource(edit.source().name());
        return row;
    }

    // ------------------------------------------------------------ engine → API

    public static RegionDto toDto(TemplateRegion region, List<PageInfo> pages, String sampleValue) {
        PageFractions.Fractions f = PageFractions.toFractions(region.box(), page(pages, region.box().page()));
        return new RegionDto(region.key(), region.label(), region.group(), region.kind().name(),
                region.box().page(), f.fx(), f.fy(), f.fw(), f.fh(), region.fontName(), region.fontSize(),
                region.color(), region.align().name(), region.maxLines(), region.acroFieldName(),
                region.required(), region.sortOrder(), region.source().name(), region.confidence(),
                sampleValue);
    }

    public static EditDto toDto(TemplateEdit edit, List<PageInfo> pages, Double confidence, String removedText) {
        PageFractions.Fractions f = PageFractions.toFractions(edit.box(), page(pages, edit.box().page()));
        return new EditDto(edit.id(), edit.kind().name(), edit.box().page(), f.fx(), f.fy(), f.fw(), f.fh(),
                edit.text(), edit.fontName(), edit.fontSize(), edit.color(), edit.imageKey(),
                edit.source().name(), confidence == null ? edit.confidence() : confidence, removedText);
    }

    // ------------------------------------------------------- API → engine (400s)

    /**
     * The editor's regions, validated: kinds, keys ([a-z0-9_]{1,64}, unique),
     * boxes inside their page, colours; sizes and line counts normalized.
     */
    public static List<TemplateRegion> fromDtos(List<RegionDto> dtos, List<PageInfo> pages) {
        List<TemplateRegion> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int index = 0;
        for (RegionDto dto : dtos == null ? List.<RegionDto>of() : dtos) {
            if (dto == null) {
                throw new IllegalArgumentException("A region is empty.");
            }
            String key = dto.key() == null ? "" : dto.key().strip();
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Region key \"" + truncate(key, 80)
                        + "\" is invalid: use 1-64 lower-case letters, digits or underscores.");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Two regions share the key \"" + key + "\".");
            }
            RegionKind kind = parse(RegionKind.class, dto.kind(), "region kind", RegionKind.TEXT);
            PdfBox box = box(dto.page(), dto.fx(), dto.fy(), dto.fw(), dto.fh(), pages, "Region \"" + key + "\"");
            String label = dto.label() == null || dto.label().isBlank() ? key : dto.label().strip();
            if (label.length() > MAX_LABEL) {
                throw new IllegalArgumentException("Region labels are limited to " + MAX_LABEL + " characters.");
            }
            String group = blankToNull(dto.group());
            if (group != null && group.length() > MAX_GROUP) {
                throw new IllegalArgumentException("Group names are limited to " + MAX_GROUP + " characters.");
            }
            float fontSize = fontSize(dto.fontSize());
            Align align = parse(Align.class, dto.align(), "alignment", Align.LEFT);
            RegionSource source = parse(RegionSource.class, dto.source(), "region source", RegionSource.MANUAL);
            double confidence = dto.confidence() == null ? 1.0 : clamp01(dto.confidence());
            int sortOrder = dto.sortOrder() == null ? index : dto.sortOrder();
            out.add(new TemplateRegion(key, label, group, kind, box, blankToNull(dto.fontName()), fontSize,
                    color(dto.color()), align, maxLines(kind, dto.maxLines()), blankToNull(dto.acroFieldName()),
                    dto.required(), sortOrder, source, confidence, null));
            index++;
        }
        return out;
    }

    /**
     * The editor's template edits, validated: kinds, boxes, the text of
     * REPLACE_TEXT/ADD_TEXT, the image of ADD_IMAGE (one of
     * {@code knownImageKeys}); a missing id gets a fresh uuid.
     */
    public static List<TemplateEdit> editsFromDtos(List<EditDto> dtos, List<PageInfo> pages,
                                                   Set<String> knownImageKeys) {
        List<TemplateEdit> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (EditDto dto : dtos == null ? List.<EditDto>of() : dtos) {
            if (dto == null) {
                throw new IllegalArgumentException("An edit is empty.");
            }
            String id = dto.id() == null || dto.id().isBlank() ? UUID.randomUUID().toString() : dto.id().strip();
            if (!EDIT_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Edit id \"" + truncate(id, 80) + "\" is invalid.");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Two edits share the id \"" + id + "\".");
            }
            EditKind kind = parse(EditKind.class, dto.kind(), "edit kind", null);
            PdfBox box = box(dto.page(), dto.fx(), dto.fy(), dto.fw(), dto.fh(), pages, "An edit");
            String text = dto.text();
            if (kind == EditKind.REPLACE_TEXT || kind == EditKind.ADD_TEXT) {
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("A text edit needs its text.");
                }
                if (text.length() > MAX_TEXT) {
                    throw new IllegalArgumentException("Edit texts are limited to " + MAX_TEXT + " characters.");
                }
            } else {
                text = null;
            }
            String imageKey = null;
            if (kind == EditKind.ADD_IMAGE) {
                imageKey = dto.imageKey() == null ? "" : dto.imageKey().strip();
                if (!knownImageKeys.contains(imageKey)) {
                    throw new IllegalArgumentException("An image edit names an image that was not uploaded "
                            + "for this template; upload it first.");
                }
            }
            EditSource source = parse(EditSource.class, dto.source(), "edit source", EditSource.USER);
            Float size = dto.fontSize() == null ? null : fontSize(dto.fontSize());
            out.add(new TemplateEdit(id, kind, box, text, blankToNull(dto.fontName()), size,
                    dto.color() == null ? null : color(dto.color()), imageKey, source,
                    dto.confidence() == null ? 1.0 : clamp01(dto.confidence()), null));
        }
        return out;
    }

    // --------------------------------------------------- engine proposals (fix)

    /**
     * The engine's proposed regions made storable: a key outside
     * [a-z0-9_]{1,64} or a duplicate becomes a slug of the label (or
     * field_N), labels and groups are cut to size, sort orders follow the
     * proposal's order. The box stays exactly as the engine found it.
     */
    public static List<TemplateRegion> normalizeProposed(List<TemplateRegion> proposed, int pageCount) {
        List<TemplateRegion> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int index = 0;
        for (TemplateRegion region : proposed == null ? List.<TemplateRegion>of() : proposed) {
            if (region == null || region.box() == null || region.box().page() < 0
                    || region.box().page() >= pageCount || !(region.box().w() > 0) || !(region.box().h() > 0)) {
                continue;
            }
            index++;
            String key = region.key() == null ? "" : region.key();
            if (!KEY.matcher(key).matches() || keys.contains(key)) {
                key = uniqueKey(slug(region.label()), keys, index);
            }
            keys.add(key);
            String label = region.label() == null || region.label().isBlank() ? key
                    : truncate(region.label().strip(), MAX_LABEL);
            String group = region.group() == null || region.group().isBlank() ? null
                    : truncate(region.group().strip(), MAX_GROUP);
            RegionKind kind = region.kind() == null ? RegionKind.TEXT : region.kind();
            out.add(new TemplateRegion(key, label, group, kind, region.box(),
                    region.fontName() == null ? null : truncate(region.fontName(), 64),
                    Math.max(0f, Math.min(MAX_FONT_SIZE, region.fontSize())),
                    region.color() != null && COLOR.matcher(region.color()).matches()
                            ? region.color() : DEFAULT_COLOR,
                    region.align() == null ? Align.LEFT : region.align(),
                    maxLines(kind, region.maxLines()),
                    region.acroFieldName() == null ? null : truncate(region.acroFieldName(), 255),
                    region.required(), out.size(),
                    region.source() == null ? RegionSource.DETECTED : region.source(),
                    clamp01(region.confidence()), region.sampleValue()));
        }
        return out;
    }

    /** The engine's proposed removals made storable (ids, kinds and sources defaulted). */
    public static List<TemplateEdit> normalizeProposedEdits(List<TemplateEdit> proposed, int pageCount) {
        List<TemplateEdit> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (TemplateEdit edit : proposed == null ? List.<TemplateEdit>of() : proposed) {
            if (edit == null || edit.box() == null || edit.box().page() < 0 || edit.box().page() >= pageCount
                    || !(edit.box().w() > 0) || !(edit.box().h() > 0)) {
                continue;
            }
            String id = edit.id() != null && EDIT_ID.matcher(edit.id()).matches() && !ids.contains(edit.id())
                    ? edit.id() : UUID.randomUUID().toString();
            ids.add(id);
            out.add(new TemplateEdit(id, edit.kind() == null ? EditKind.REMOVE : edit.kind(), edit.box(),
                    edit.text(), edit.fontName() == null ? null : truncate(edit.fontName(), 64),
                    edit.fontSize(), edit.color() != null && COLOR.matcher(edit.color()).matches()
                            ? edit.color() : null,
                    edit.imageKey(), edit.source() == null ? EditSource.DATA_REMOVAL : edit.source(),
                    clamp01(edit.confidence()), edit.removedText()));
        }
        return out;
    }

    /** "Full name (as on ID)" → "full_name_as_on_id"; empty when nothing usable is left. */
    public static String slug(String text) {
        if (text == null) {
            return "";
        }
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.length() > 56 ? slug.substring(0, 56).replaceAll("_+$", "") : slug;
    }

    // ------------------------------------------------------------- helpers

    public static PageInfo page(List<PageInfo> pages, int index) {
        if (index < 0 || index >= pages.size()) {
            throw new IllegalArgumentException("Page " + index + " does not exist in this template.");
        }
        return pages.get(index);
    }

    private static PdfBox box(int page, double fx, double fy, double fw, double fh, List<PageInfo> pages,
                              String what) {
        PageInfo info;
        try {
            info = page(pages, page);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " is on page " + page + ", which does not exist.");
        }
        try {
            return PageFractions.toBox(page, new PageFractions.Fractions(fx, fy, fw, fh), info);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ": " + e.getMessage());
        }
    }

    private static int maxLines(RegionKind kind, Integer requested) {
        return switch (kind) {
            case TEXT, DATE, NUMBER -> 1;
            case MULTILINE -> requested == null || requested < 1 ? 1 : Math.min(requested, 500);
            case CHECKBOX, IMAGE, SIGNATURE -> 0;
        };
    }

    private static float fontSize(Float size) {
        if (size == null) {
            return 0f;
        }
        if (!Float.isFinite(size) || size < 0 || size > MAX_FONT_SIZE) {
            throw new IllegalArgumentException("Font sizes must be between 0 (auto) and " + (int) MAX_FONT_SIZE
                    + " points.");
        }
        return size;
    }

    private static String color(String color) {
        if (color == null || color.isBlank()) {
            return DEFAULT_COLOR;
        }
        if (!COLOR.matcher(color.strip()).matches()) {
            throw new IllegalArgumentException("Colours are written #RRGGBB.");
        }
        return color.strip().toUpperCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String what, E fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new IllegalArgumentException("A " + what + " is required.");
            }
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + what + " \"" + truncate(value, 40) + "\".");
        }
    }

    private static String uniqueKey(String base, Set<String> taken, int index) {
        String root = base == null || base.isEmpty() ? "field_" + index : base;
        String key = root;
        for (int n = 2; taken.contains(key); n++) {
            key = root + "_" + n;
        }
        return key;
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
