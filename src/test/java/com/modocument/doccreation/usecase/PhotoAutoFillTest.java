package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import com.modocument.testsupport.FakePdfTemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoAutoFillTest {

    private static TemplateRegion box(String key, String label, String group, RegionKind kind, int sort, float y) {
        return FakePdfTemplateEngine.region(key, label, group, kind, new PdfBox(0, 50, y, 100, 100), sort, null);
    }

    private static final List<TemplateRegion> REGIONS = List.of(
            box("name", "Name", null, RegionKind.TEXT, 0, 700),
            box("photo_front", "Front of house", "Site photos", RegionKind.IMAGE, 1, 600),
            box("photo_roof", "Roof", "Site photos", RegionKind.IMAGE, 2, 400),
            box("photo_meter", "Electricity meter", "Site photos", RegionKind.IMAGE, 3, 200),
            box("signature", "Customer signature", null, RegionKind.SIGNATURE, 4, 100));

    @Test
    void fileNamesMatchLabelsKeysAndGroupsBeforeReadingOrder() {
        Map<Integer, String> placed = PhotoAutoFill.assign(List.of(
                new PhotoAutoFill.Photo(1, "IMG_0001.jpg"),
                new PhotoAutoFill.Photo(2, "roof-east.JPG"),
                new PhotoAutoFill.Photo(3, "meterReading.png"),
                new PhotoAutoFill.Photo(4, "customer_signature.png")), REGIONS, Set.of());

        assertThat(placed).containsEntry(2, "photo_roof")
                .containsEntry(3, "photo_meter")
                .containsEntry(4, "signature")
                .containsEntry(1, "photo_front");
    }

    @Test
    void numbersAloneNeverMatchAndSignaturesAreNeverFilledByOrder() {
        Map<Integer, String> placed = PhotoAutoFill.assign(List.of(
                new PhotoAutoFill.Photo(1, "4.jpg"),
                new PhotoAutoFill.Photo(2, "DSC_9.jpg"),
                new PhotoAutoFill.Photo(3, "IMG_7.jpg"),
                new PhotoAutoFill.Photo(4, "IMG_8.jpg")), REGIONS, Set.of());

        assertThat(placed).containsEntry(1, "photo_front").containsEntry(2, "photo_roof")
                .containsEntry(3, "photo_meter").doesNotContainKey(4);
    }

    @Test
    void filledBoxesAreSkippedAndTheBestNameWins() {
        Map<Integer, String> placed = PhotoAutoFill.assign(List.of(
                new PhotoAutoFill.Photo(1, "site_roof.png"),
                new PhotoAutoFill.Photo(2, "roof.png")), REGIONS, Set.of("photo_front"));

        // "site roof" shares two tokens with photo_roof (group "Site photos" + "roof").
        assertThat(placed).containsEntry(1, "photo_roof").containsEntry(2, "photo_meter");
    }

    @Test
    void tokensSplitCamelCaseDigitsAndDropNoise() {
        assertThat(PhotoAutoFill.tokens("frontOfHouse2_IMG.jpeg")).containsExactly("front", "house", "2");
    }
}
