package com.modocument.doccreation.usecase;

import com.modocument.doccreation.dto.EditDto;
import com.modocument.doccreation.dto.RegionDto;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.fields.FieldPropsCodec;
import com.modocument.doccreation.pdftemplate.EditKind;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateEdit;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlayMapperTest {

    private static final List<PageInfo> PAGES = List.of(new PageInfo(0, 600, 800, 0));

    private static RegionDto region(String key, String kind, Integer maxLines) {
        return new RegionDto(key, null, " ", kind, 0, 0.1, 0.1, 0.2, 0.05, null, null, null, null, maxLines,
                null, false, null, null, null, "ignored sample", null, null);
    }

    @Test
    void editorRegionsAreNormalized() {
        List<TemplateRegion> regions = OverlayMapper.fromDtos(List.of(
                region("name", "text", 5), region("notes", "MULTILINE", 4), region("photo", "IMAGE", 3)), PAGES);

        assertThat(regions.get(0).maxLines()).as("TEXT is one line").isEqualTo(1);
        assertThat(regions.get(0).label()).as("a blank label falls back to the key").isEqualTo("name");
        assertThat(regions.get(0).group()).isNull();
        assertThat(regions.get(0).color()).isEqualTo("#000000");
        assertThat(regions.get(0).sampleValue()).as("never taken from input").isNull();
        assertThat(regions.get(1).maxLines()).isEqualTo(4);
        assertThat(regions.get(2).maxLines()).isZero();
        assertThat(regions.get(2).sortOrder()).isEqualTo(2);
    }

    @Test
    void invalidEditorInputIsRefused() {
        assertThatThrownBy(() -> OverlayMapper.fromDtos(List.of(region("Bad Key", "TEXT", 1)), PAGES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OverlayMapper.fromDtos(List.of(region("a", "TEXT", 1), region("a", "TEXT", 1)),
                PAGES)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("share");
        RegionDto badColor = new RegionDto("a", "A", null, "TEXT", 0, 0.1, 0.1, 0.1, 0.1, null, null, "red",
                null, null, null, false, null, null, null, null, null, null);
        assertThatThrownBy(() -> OverlayMapper.fromDtos(List.of(badColor), PAGES))
                .isInstanceOf(IllegalArgumentException.class);
        EditDto textless = new EditDto(null, "ADD_TEXT", 0, 0.1, 0.1, 0.1, 0.1, " ", null, null, null, null, null,
                null, null, null, null);
        assertThatThrownBy(() -> OverlayMapper.editsFromDtos(List.of(textless), PAGES, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        EditDto unknownImage = new EditDto(null, "ADD_IMAGE", 0, 0.1, 0.1, 0.1, 0.1, null, null, null, null,
                "img_0000aaaa", null, null, null, true, null);
        assertThatThrownBy(() -> OverlayMapper.editsFromDtos(List.of(unknownImage), PAGES, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editsGetIdsAndKeepOnlyTheirOwnPayload() {
        EditDto remove = new EditDto(null, "REMOVE", 0, 0.1, 0.1, 0.1, 0.1, "dropped", null, null, null,
                "img_x", null, null, "removed text", null, "full_name");
        var edits = OverlayMapper.editsFromDtos(List.of(remove), PAGES, Set.of());

        assertThat(edits.get(0).id()).isNotBlank();
        assertThat(edits.get(0).kind()).isEqualTo(EditKind.REMOVE);
        assertThat(edits.get(0).text()).isNull();
        assertThat(edits.get(0).imageKey()).isNull();
        assertThat(edits.get(0).removedText()).isNull();
    }

    /** BUG-PDF-01: unapplied suggestions and unkept suggested regions are ignored, even when invalid. */
    @Test
    void unappliedSuggestionsAndUnkeptSuggestedRegionsAreIgnored() {
        EditDto suggestion = new EditDto("s-1", "REMOVE", 0, 0.1, 0.1, 0.1, 0.1, null, null, null, null, null,
                "DATA_REMOVAL", 0.45, "maybe data", false, "contact");
        EditDto brokenSuggestion = new EditDto("s-2", "NOT_A_KIND", 9, 0.1, 0.1, 0.1, 0.1, null, null, null,
                null, null, null, null, null, false, null);
        EditDto applied = new EditDto("a-1", "REMOVE", 0, 0.2, 0.2, 0.1, 0.1, null, null, null, null, null,
                "DATA_REMOVAL", 0.45, null, true, "contact");
        EditDto legacy = new EditDto("a-2", "REMOVE", 0, 0.3, 0.3, 0.1, 0.1, null, null, null, null, null,
                null, null, null, null, null);
        assertThat(OverlayMapper.editsFromDtos(List.of(suggestion, brokenSuggestion, applied, legacy), PAGES,
                Set.of())).extracting(e -> e.id()).containsExactly("a-1", "a-2");

        RegionDto suggested = new RegionDto("contact", "Contact", null, "TEXT", 0, 0.1, 0.1, 0.2, 0.05, null,
                null, null, null, null, null, false, null, "DATA_REMOVED", 0.45, "maybe data", true, null);
        RegionDto duplicateButSuggested = new RegionDto("name", "Name", null, "TEXT", 0, 0.1, 0.1, 0.2, 0.05,
                null, null, null, null, null, null, false, null, null, null, null, true, null);
        RegionDto kept = new RegionDto("name", "Name", null, "TEXT", 0, 0.1, 0.1, 0.2, 0.05, null, null, null,
                null, null, null, false, null, null, null, null, false, null);
        assertThat(OverlayMapper.fromDtos(List.of(suggested, duplicateButSuggested, kept), PAGES))
                .extracting(TemplateRegion::key).containsExactly("name");
    }

    @Test
    void proposedRemovalsKeepTheirValidRegionLink() {
        TemplateEdit linked = new TemplateEdit("e1", EditKind.REMOVE, new PdfBox(0, 1, 1, 10, 10), null, null,
                null, null, null, null, 0.5, "x", "contact");
        TemplateEdit badLink = new TemplateEdit("e2", EditKind.REMOVE, new PdfBox(0, 1, 20, 10, 10), null, null,
                null, null, null, null, 0.5, "y", "Not A Key");
        assertThat(OverlayMapper.normalizeProposedEdits(List.of(linked, badLink), 1))
                .extracting(TemplateEdit::regionKey).containsExactly("contact", null);
    }

    @Test
    void engineProposalsAreRepairedNotRefused() {
        TemplateRegion bad = FakePdfTemplateEngine.region("Full Name", "Full name (as on ID)", null,
                RegionKind.TEXT, new PdfBox(0, 1, 1, 10, 10), 0, "Jane");
        TemplateRegion duplicate = FakePdfTemplateEngine.region("full_name_as_on_id", "Full name (as on ID)",
                null, RegionKind.TEXT, new PdfBox(0, 1, 30, 10, 10), 1, null);
        TemplateRegion offPage = FakePdfTemplateEngine.region("x", "X", null, RegionKind.TEXT,
                new PdfBox(4, 1, 1, 10, 10), 2, null);

        List<TemplateRegion> fixed = OverlayMapper.normalizeProposed(List.of(bad, duplicate, offPage), 1);

        assertThat(fixed).extracting(TemplateRegion::key)
                .containsExactly("full_name_as_on_id", "full_name_as_on_id_2");
        assertThat(fixed.get(0).sampleValue()).as("kept for the review only").isEqualTo("Jane");
    }

    // ------------------------------------------------- ws33 field properties

    private static RegionDto withProps(String key, String kind, FieldProps props) {
        return new RegionDto(key, null, null, kind, 0, 0.1, 0.1, 0.2, 0.05, null, null, null, null, 3,
                null, false, null, null, null, null, null, props);
    }

    private static FieldProps props(String placeholder, String helpText, String defaultValue, List<String> options,
                                    String dateFormat, FieldProps.NumberFormatSpec numberFormat, Integer maxLength) {
        return new FieldProps(placeholder, helpText, defaultValue, options, dateFormat, numberFormat, maxLength);
    }

    private static FieldProps propsOf(RegionDto dto) {
        return OverlayMapper.overlayFromDtos(List.of(dto), PAGES).propsOf(dto.key());
    }

    @Test
    void choiceIsOneLineAndNeedsOptions() {
        RegionDto choice = withProps("status", "choice", props(null, null, "Married", List.of(" Single ", "Married"),
                null, null, 30));
        OverlayMapper.Overlay overlay = OverlayMapper.overlayFromDtos(List.of(choice), PAGES);

        assertThat(overlay.regions().get(0).kind()).isEqualTo(RegionKind.CHOICE);
        assertThat(overlay.regions().get(0).maxLines()).as("CHOICE is one line").isEqualTo(1);
        assertThat(overlay.propsOf("status").options()).as("trimmed").containsExactly("Single", "Married");
        assertThat(overlay.propsOf("status").defaultValue()).isEqualTo("Married");
        assertThat(overlay.propsOf("status").maxLength()).isEqualTo(30);

        assertThatThrownBy(() -> propsOf(withProps("status", "CHOICE", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Field \"status\": ").hasMessageContaining("at least one option");
        assertThatThrownBy(() -> propsOf(withProps("status", "CHOICE", props(null, null, null, List.of(), null,
                null, null)))).hasMessageStartingWith("Field \"status\": ");
        assertThatThrownBy(() -> propsOf(withProps("status", "CHOICE", props(null, null, null,
                List.of("A", " A"), null, null, null)))).hasMessageContaining("listed twice");
        assertThatThrownBy(() -> propsOf(withProps("status", "CHOICE", props(null, null, null,
                List.of("A", "  "), null, null, null)))).hasMessageContaining("1 to 200 characters");
        assertThatThrownBy(() -> propsOf(withProps("status", "CHOICE", props(null, null, "Divorced",
                List.of("Single", "Married"), null, null, null)))).hasMessageContaining("one of the options");
        List<String> tooMany = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "o" + i).toList();
        assertThatThrownBy(() -> propsOf(withProps("status", "CHOICE", props(null, null, null, tooMany, null,
                null, null)))).hasMessageContaining("at most 200 options");
    }

    @Test
    void propertiesThatDoNotFitTheKindAreDropped() {
        FieldProps all = props("e.g. Jane", "As on the ID card", null, List.of("A", "B"), "dd/MM/yyyy",
                new FieldProps.NumberFormatSpec(2, true, "Rs ", null), 40);

        FieldProps text = propsOf(withProps("name", "TEXT", all));
        assertThat(text.placeholder()).isEqualTo("e.g. Jane");
        assertThat(text.helpText()).isEqualTo("As on the ID card");
        assertThat(text.options()).as("CHOICE only").isNull();
        assertThat(text.dateFormat()).as("DATE only").isNull();
        assertThat(text.numberFormat()).as("NUMBER only").isNull();
        assertThat(text.maxLength()).isEqualTo(40);

        FieldProps date = propsOf(withProps("dob", "DATE", all));
        assertThat(date.dateFormat()).isEqualTo("dd/MM/yyyy");
        assertThat(date.maxLength()).as("text kinds only").isNull();
        assertThat(date.options()).isNull();

        FieldProps number = propsOf(withProps("amount", "NUMBER", all));
        assertThat(number.numberFormat()).isEqualTo(new FieldProps.NumberFormatSpec(2, true, "Rs ", null));
        assertThat(number.dateFormat()).isNull();
        assertThat(number.maxLength()).isNull();

        FieldProps photo = propsOf(withProps("photo", "IMAGE", props(null, "Face visible", "x", null, null, null,
                null)));
        assertThat(photo.helpText()).isEqualTo("Face visible");
        assertThat(photo.defaultValue()).as("a photo has no default value").isNull();

        assertThat(propsOf(withProps("blank", "TEXT", props(" ", "", null, null, null, null, null))))
                .as("blank texts are not stored").isEqualTo(FieldProps.NONE);
    }

    @Test
    void propertyLimitsAreRefusedWithTheFieldKey() {
        assertThatThrownBy(() -> propsOf(withProps("name", "TEXT", props("x".repeat(201), null, null, null, null,
                null, null)))).hasMessageStartingWith("Field \"name\": ").hasMessageContaining("200");
        assertThatThrownBy(() -> propsOf(withProps("name", "TEXT", props(null, "x".repeat(501), null, null, null,
                null, null)))).hasMessageStartingWith("Field \"name\": ").hasMessageContaining("500");
        assertThatThrownBy(() -> propsOf(withProps("name", "TEXT", props(null, null, "x".repeat(2001), null, null,
                null, null)))).hasMessageStartingWith("Field \"name\": ").hasMessageContaining("2000");
        assertThatThrownBy(() -> propsOf(withProps("name", "TEXT", props(null, null, null, null, null, null, 0))))
                .hasMessageStartingWith("Field \"name\": ").hasMessageContaining("maximum length");
        assertThatThrownBy(() -> propsOf(withProps("name", "TEXT", props(null, null, null, null, null, null,
                20001)))).hasMessageContaining("maximum length");
        assertThatThrownBy(() -> propsOf(withProps("name", "TEXT", props(null, null, "too long", null, null, null,
                3)))).hasMessageContaining("longer than the maximum length");
        assertThatThrownBy(() -> propsOf(withProps("dob", "DATE", props(null, null, null, null, "yyyy/dd/MM", null,
                null)))).hasMessageStartingWith("Field \"dob\": ").hasMessageContaining("date format");
        assertThatThrownBy(() -> propsOf(withProps("amount", "NUMBER", props(null, null, null, null, null,
                new FieldProps.NumberFormatSpec(7, null, null, null), null)))).hasMessageContaining("decimals");
        assertThatThrownBy(() -> propsOf(withProps("amount", "NUMBER", props(null, null, null, null, null,
                new FieldProps.NumberFormatSpec(null, null, "USD $ ", null), null)))).hasMessageContaining("prefix");
        assertThatCode(() -> propsOf(withProps("amount", "NUMBER", props(null, null, null, null, null,
                new FieldProps.NumberFormatSpec(null, null, null, "euros"), null)))).doesNotThrowAnyException();
    }

    @Test
    void defaultValuesPassTheBuiltInChecksOfTheirKind() {
        assertThat(propsOf(withProps("dob", "DATE", props(null, null, "2026-12-31", null, null, null, null)))
                .defaultValue()).isEqualTo("2026-12-31");
        assertThatThrownBy(() -> propsOf(withProps("dob", "DATE", props(null, null, "31/12/2026", null, null, null,
                null)))).hasMessageStartingWith("Field \"dob\": ").hasMessageContaining("yyyy-MM-dd");
        assertThat(propsOf(withProps("amount", "NUMBER", props(null, null, "1,250.50", null, null, null, null)))
                .defaultValue()).isEqualTo("1,250.50");
        assertThatThrownBy(() -> propsOf(withProps("amount", "NUMBER", props(null, null, "a lot", null, null, null,
                null)))).hasMessageContaining("number");
        assertThat(propsOf(withProps("agree", "CHECKBOX", props(null, null, "TRUE", null, null, null, null)))
                .defaultValue()).isEqualTo("true");
        assertThatThrownBy(() -> propsOf(withProps("agree", "CHECKBOX", props(null, null, "yes please", null, null,
                null, null)))).hasMessageContaining("checkbox");
    }

    @Test
    void propertiesRoundTripThroughTheRowAndTheDto() {
        FieldProps stored = new FieldProps("p", null, "Single", List.of("Single", "Married"), null, null, 20);
        TemplateRegion region = FakePdfTemplateEngine.region("status", "Status", null, RegionKind.CHOICE,
                new PdfBox(0, 10, 10, 100, 20), 0, null);

        var row = OverlayMapper.toRow(region, 1, 2, 3, stored);
        assertThat(FieldPropsCodec.read(row.getFieldPropsJson())).isEqualTo(stored);
        assertThat(OverlayMapper.toRow(region, 1, 2, 3, FieldProps.NONE).getFieldPropsJson())
                .as("empty properties are stored as null").isNull();
        assertThat(OverlayMapper.toRow(region, 1, 2, 3).getFieldPropsJson()).isNull();

        RegionDto dto = OverlayMapper.toDto(region, PAGES, null, false, stored, true);
        assertThat(dto.props()).isEqualTo(stored);
        assertThat(dto.required()).as("derived, not the stored flag").isTrue();
        assertThat(OverlayMapper.toDto(region, PAGES, null, false, FieldProps.NONE, false).props()).isNull();
        assertThat(OverlayMapper.toDto(region, PAGES, null).props()).isNull();
    }

    @Test
    void fieldPropsCodecIsLenient() {
        assertThat(FieldPropsCodec.read(null)).isEqualTo(FieldProps.NONE);
        assertThat(FieldPropsCodec.read(" ")).isEqualTo(FieldProps.NONE);
        assertThat(FieldPropsCodec.read("{not json")).isEqualTo(FieldProps.NONE);
        assertThat(FieldPropsCodec.read("{\"placeholder\":\"x\",\"unknown\":1}").placeholder()).isEqualTo("x");
        assertThat(FieldPropsCodec.write(null)).isNull();
        assertThat(FieldPropsCodec.write(new FieldProps(null, null, null, null, null,
                new FieldProps.NumberFormatSpec(2, null, null, null), null)))
                .isEqualTo("{\"numberFormat\":{\"decimals\":2}}");
    }

    @Test
    void slugsAreAsciiKeys() {
        assertThat(OverlayMapper.slug("Prénom / Date de naissance")).isEqualTo("prenom_date_de_naissance");
        assertThat(OverlayMapper.slug("!!!")).isEmpty();
    }
}
