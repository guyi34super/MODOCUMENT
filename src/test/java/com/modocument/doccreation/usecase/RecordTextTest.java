package com.modocument.doccreation.usecase;

import com.modocument.doccreation.entity.TemplateRecordKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The text rules of the record index (flow-records-contract §1). */
class RecordTextTest {

    @Test
    void searchTextIsLowerCasedAccentFoldedAndJoined() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("full_name", "  Zoë ÉLODIE Smith ");
        values.put("nic", "J1234567");
        values.put("empty", "");
        values.put("missing", null);

        assertThat(RecordText.searchText(values)).isEqualTo("zoe elodie smith j1234567");
        assertThat(RecordText.fold("Ångström")).isEqualTo("angstrom");
        assertThat(RecordText.words("  Zoë   SMITH ")).containsExactly("zoe", "smith");
        assertThat(RecordText.words("  ")).isEmpty();
        assertThat(RecordText.words(null)).isEmpty();
    }

    @Test
    void keysAreTrimmedLowerCasedWithoutWhitespaceOrPunctuation() {
        assertThat(RecordText.key("  J-123 4567. ")).isEqualTo("j1234567");
        assertThat(RecordText.key("J1234567")).isEqualTo(RecordText.key("j 123-456/7"));
        assertThat(RecordText.key("+230 5 123-4567")).isEqualTo("23051234567");
        assertThat(RecordText.key("jane.doe@acme.mu")).isEqualTo("janedoeacmemu");
        assertThat(RecordText.key("   ")).isEmpty();
        assertThat(RecordText.key(null)).isEmpty();
        assertThat(RecordText.key("x".repeat(400))).hasSize(TemplateRecordKey.MAX_KEY_LENGTH);
    }

    @Test
    void sameNameComparesTheNormalisedFieldWithTheRegionKeyOrLabel() {
        assertThat(RecordText.sameName("full_name", "full_name", "Name")).isTrue();
        assertThat(RecordText.sameName("FullName", "applicant", "Full name")).isTrue();
        assertThat(RecordText.sameName("nic", "id_number", "NIC")).isTrue();
        assertThat(RecordText.sameName("dob", "date_of_birth", "Date of birth")).isFalse();
        assertThat(RecordText.sameName("", "x", "x")).isFalse();
    }

    @Test
    void likePatternsEscapeTheWildcards() {
        assertThat(TemplateRecordUseCase.contains("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }
}
