package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.PageInfo;
import com.modocument.doccreation.pdftemplate.PdfBox;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The contract §2 mapping between PDF-point boxes and displayed top-left fractions. */
class PageFractionsTest {

    private static final PdfBox BOX = new PdfBox(0, 100, 700, 200, 20);

    @Test
    void unrotatedPagesUseTopLeftFractions() {
        PageFractions.Fractions f = PageFractions.toFractions(BOX, new PageInfo(0, 600, 800, 0));

        assertThat(f.fx()).isCloseTo(100.0 / 600, within(1e-9));
        assertThat(f.fy()).isCloseTo(1 - 720.0 / 800, within(1e-9));
        assertThat(f.fw()).isCloseTo(200.0 / 600, within(1e-9));
        assertThat(f.fh()).isCloseTo(20.0 / 800, within(1e-9));
    }

    @Test
    void rotationsMapTheBoxLikeTheDisplayedImage() {
        // Unrotated top-left fractions of BOX on 600x800: (1/6, 0.1, 1/3, 0.025).
        double ux = 100.0 / 600;
        double uy = 0.1;
        double uw = 200.0 / 600;
        double uh = 0.025;
        PageFractions.Fractions r90 = PageFractions.toFractions(BOX, new PageInfo(0, 600, 800, 90));
        assertThat(r90.fx()).isCloseTo(1 - (uy + uh), within(1e-9));
        assertThat(r90.fy()).isCloseTo(ux, within(1e-9));
        assertThat(r90.fw()).isCloseTo(uh, within(1e-9));
        assertThat(r90.fh()).isCloseTo(uw, within(1e-9));

        PageFractions.Fractions r180 = PageFractions.toFractions(BOX, new PageInfo(0, 600, 800, 180));
        assertThat(r180.fx()).isCloseTo(1 - (ux + uw), within(1e-9));
        assertThat(r180.fy()).isCloseTo(1 - (uy + uh), within(1e-9));

        PageFractions.Fractions r270 = PageFractions.toFractions(BOX, new PageInfo(0, 600, 800, 270));
        assertThat(r270.fx()).isCloseTo(uy, within(1e-9));
        assertThat(r270.fy()).isCloseTo(1 - (ux + uw), within(1e-9));
        assertThat(r270.fw()).isCloseTo(uh, within(1e-9));
        assertThat(r270.fh()).isCloseTo(uw, within(1e-9));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270, -90, 450})
    void theMappingRoundTripsForEveryRotation(int rotation) {
        PageInfo page = new PageInfo(0, 612, 792, rotation);
        PdfBox back = PageFractions.toBox(0, PageFractions.toFractions(BOX, page), page);

        assertThat(back.x()).isCloseTo(100f, within(0.01f));
        assertThat(back.y()).isCloseTo(700f, within(0.01f));
        assertThat(back.w()).isCloseTo(200f, within(0.01f));
        assertThat(back.h()).isCloseTo(20f, within(0.01f));
    }

    @Test
    void boxesOutsideThePageOrEmptyAreRefused() {
        PageInfo page = new PageInfo(0, 600, 800, 0);
        assertThatThrownBy(() -> PageFractions.toBox(0, new PageFractions.Fractions(0.9, 0.1, 0.2, 0.1), page))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageFractions.toBox(0, new PageFractions.Fractions(0.1, 0.1, 0, 0.1), page))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageFractions.toBox(0, new PageFractions.Fractions(Double.NaN, 0.1, 0.1, 0.1),
                page)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PageFractions.toBox(0, new PageFractions.Fractions(0, 0, 1, 1), page))
                .isEqualTo(new PdfBox(0, 0, 0, 600, 800));
    }
}
