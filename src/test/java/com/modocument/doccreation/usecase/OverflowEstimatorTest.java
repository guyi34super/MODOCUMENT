package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverflowEstimatorTest {

    private static final TemplateRegion NAME = FakePdfTemplateEngine.region("name", "Name", null, RegionKind.TEXT,
            new PdfBox(0, 0, 0, 100, 12), 0, null);
    private static final TemplateRegion NOTES = FakePdfTemplateEngine.region("notes", "Notes", null,
            RegionKind.MULTILINE, new PdfBox(0, 0, 0, 100, 30), 1, null);
    private static final TemplateRegion PHOTO = FakePdfTemplateEngine.region("photo", "Photo", null,
            RegionKind.IMAGE, new PdfBox(0, 0, 0, 10, 10), 2, null);

    @Test
    void shortValuesFitAndLongOnesOverflowAtTheMinimumSize() {
        List<TemplateRegion> regions = List.of(NAME, NOTES, PHOTO);

        assertThat(OverflowEstimator.overflowing(regions, Map.of("name", "Jane", "notes", "Fine."), 6f)).isEmpty();
        assertThat(OverflowEstimator.overflowing(regions,
                Map.of("name", "A very long name that cannot fit one hundred points"), 6f)).containsExactly("name");
        // 3 lines allowed (maxLines 3; 30pt / 7.2pt = 4 lines of box).
        assertThat(OverflowEstimator.overflowing(regions, Map.of("notes", "a\nb\nc"), 6f)).isEmpty();
        assertThat(OverflowEstimator.overflowing(regions, Map.of("notes", "a\nb\nc\nd"), 6f))
                .containsExactly("notes");
        assertThat(OverflowEstimator.overflowing(regions, Map.of("photo", "x".repeat(500)), 6f)).isEmpty();
    }

    @Test
    void aLargerMinimumOverflowsSooner() {
        String value = "Twenty two characters";
        assertThat(OverflowEstimator.overflowing(List.of(NAME), Map.of("name", value), 4f)).isEmpty();
        assertThat(OverflowEstimator.overflowing(List.of(NAME), Map.of("name", value), 10f)).containsExactly("name");
    }
}
