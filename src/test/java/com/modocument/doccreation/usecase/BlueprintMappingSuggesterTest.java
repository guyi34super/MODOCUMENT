package com.modocument.doccreation.usecase;

import com.modocument.doccreation.pdftemplate.Align;
import com.modocument.doccreation.pdftemplate.PdfBox;
import com.modocument.doccreation.pdftemplate.RegionKind;
import com.modocument.doccreation.pdftemplate.RegionSource;
import com.modocument.doccreation.pdftemplate.TemplateRegion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Auto-mapping rules of flow-records-contract §4. */
class BlueprintMappingSuggesterTest {

    private static TemplateRegion region(String key, String label, RegionKind kind) {
        return new TemplateRegion(key, label, null, kind, new PdfBox(0, 10, 10, 100, 20), "Helvetica", 0f,
                "#000000", Align.LEFT, 1, null, false, 0, RegionSource.MANUAL, 1.0, null);
    }

    private static BlueprintMappingSuggester.Field field(String name, String type, String... aliases) {
        return new BlueprintMappingSuggester.Field(name, type, List.of(aliases));
    }

    private static Map<String, String> autoMap(List<TemplateRegion> regions,
                                               List<BlueprintMappingSuggester.Field> fields) {
        return BlueprintMappingSuggester.assign(regions,
                BlueprintMappingSuggester.candidates(regions, fields, Map.of()), 0.7);
    }

    @Test
    void sameNameScoresOneAndConnects() {
        List<TemplateRegion> regions = List.of(region("full_name", "Applicant", RegionKind.TEXT),
                region("id_number", "NIC", RegionKind.TEXT));
        List<BlueprintMappingSuggester.Field> fields = List.of(field("FullName", "Text"), field("nic", "Text"));

        List<BlueprintMappingSuggester.Candidate> candidates =
                BlueprintMappingSuggester.candidates(regions, fields, Map.of());

        assertThat(candidates).contains(
                new BlueprintMappingSuggester.Candidate("full_name", "FullName", 1.0, true),
                new BlueprintMappingSuggester.Candidate("id_number", "nic", 1.0, true));
        assertThat(autoMap(regions, fields)).containsExactly(
                Map.entry("full_name", "FullName"), Map.entry("id_number", "nic"));
    }

    @Test
    void builtInFieldAndBankAliasesScoreAboveTheThreshold() {
        List<TemplateRegion> regions = List.of(region("date_of_birth", "Date of birth", RegionKind.DATE),
                region("employee", "Employee number", RegionKind.TEXT),
                region("tel", "Contact", RegionKind.TEXT));
        List<BlueprintMappingSuggester.Field> fields = List.of(field("dob", "Date"),
                field("matricule", "Text"), field("mobile_no", "Text", "Contact"));

        List<BlueprintMappingSuggester.Candidate> candidates = BlueprintMappingSuggester.candidates(regions,
                fields, Map.of("matricule", "employeenumber"));

        assertThat(candidates).contains(new BlueprintMappingSuggester.Candidate("date_of_birth", "dob", 0.95, false));
        Map<String, String> mapped = BlueprintMappingSuggester.assign(regions, candidates, 0.7);
        assertThat(mapped).containsEntry("date_of_birth", "dob")
                .containsEntry("employee", "matricule")   // bank alias (mapping.aliases)
                .containsEntry("tel", "mobile_no");        // the field's own alias
    }

    @Test
    void assignmentIsOneToOneBestScoreFirstAndRespectsTheThreshold() {
        List<TemplateRegion> regions = List.of(region("name", "Name", RegionKind.TEXT),
                region("full_name", "Full name", RegionKind.TEXT),
                region("remarks", "Remarks", RegionKind.MULTILINE));
        List<BlueprintMappingSuggester.Field> fields = List.of(field("full_name", "Text"), field("city", "Text"));

        Map<String, String> mapped = autoMap(regions, fields);

        // full_name is a same-name match for "full_name" (1.0) and only similar
        // to "name": it goes to the best region, once.
        assertThat(mapped).containsExactly(Map.entry("full_name", "full_name"));
        assertThat(mapped).doesNotContainKeys("name", "remarks");
    }

    @Test
    void kindsMustBeCompatibleAndPhotosAreNeverMapped() {
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.DATE, "Date")).isTrue();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.DATE, "Text")).isTrue();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.DATE, "Number")).isFalse();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.NUMBER, "Number")).isTrue();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.NUMBER, "Text")).isTrue();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.NUMBER, "Date")).isFalse();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.CHECKBOX, "Number")).isTrue();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.TEXT, "Date")).isTrue();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.IMAGE, "Text")).isFalse();
        assertThat(BlueprintMappingSuggester.compatible(RegionKind.SIGNATURE, "Text")).isFalse();

        List<TemplateRegion> regions = List.of(region("amount", "Amount", RegionKind.NUMBER),
                region("photo", "Photo", RegionKind.IMAGE));
        List<BlueprintMappingSuggester.Field> fields = List.of(field("amount", "Date"), field("photo", "Text"));
        assertThat(autoMap(regions, fields)).isEmpty();
    }
}
