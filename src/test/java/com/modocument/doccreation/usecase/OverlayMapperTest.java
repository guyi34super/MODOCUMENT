package com.modocument.doccreation.usecase;

import com.modocument.doccreation.dto.EditDto;
import com.modocument.doccreation.dto.RegionDto;
import com.modocument.doccreation.pdftemplate.EditKind;
import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlayMapperTest {

    private static final List<PageInfo> PAGES = List.of(new PageInfo(0, 600, 800, 0));

    private static RegionDto region(String key, String kind, Integer maxLines) {
        return new RegionDto(key, null, " ", kind, 0, 0.1, 0.1, 0.2, 0.05, null, null, null, null, maxLines,
                null, false, null, null, null, "ignored sample");
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
                null, null, null, false, null, null, null, null);
        assertThatThrownBy(() -> OverlayMapper.fromDtos(List.of(badColor), PAGES))
                .isInstanceOf(IllegalArgumentException.class);
        EditDto textless = new EditDto(null, "ADD_TEXT", 0, 0.1, 0.1, 0.1, 0.1, " ", null, null, null, null, null,
                null, null);
        assertThatThrownBy(() -> OverlayMapper.editsFromDtos(List.of(textless), PAGES, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        EditDto unknownImage = new EditDto(null, "ADD_IMAGE", 0, 0.1, 0.1, 0.1, 0.1, null, null, null, null,
                "img_0000aaaa", null, null, null);
        assertThatThrownBy(() -> OverlayMapper.editsFromDtos(List.of(unknownImage), PAGES, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editsGetIdsAndKeepOnlyTheirOwnPayload() {
        EditDto remove = new EditDto(null, "REMOVE", 0, 0.1, 0.1, 0.1, 0.1, "dropped", null, null, null,
                "img_x", null, null, "removed text");
        var edits = OverlayMapper.editsFromDtos(List.of(remove), PAGES, Set.of());

        assertThat(edits.get(0).id()).isNotBlank();
        assertThat(edits.get(0).kind()).isEqualTo(EditKind.REMOVE);
        assertThat(edits.get(0).text()).isNull();
        assertThat(edits.get(0).imageKey()).isNull();
        assertThat(edits.get(0).removedText()).isNull();
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

    @Test
    void slugsAreAsciiKeys() {
        assertThat(OverlayMapper.slug("Prénom / Date de naissance")).isEqualTo("prenom_date_de_naissance");
        assertThat(OverlayMapper.slug("!!!")).isEmpty();
    }
}
