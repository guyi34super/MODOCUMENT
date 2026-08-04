package com.modocument.application.mapping;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnNameSimilarityTest {

    @Test
    void tokenizesCamelCaseSnakeCaseAndSpaces() {
        assertThat(ColumnNameSimilarity.expandedTokens("empFirstNm", Map.of()))
                .contains("emp", "first");
        assertThat(ColumnNameSimilarity.expandedTokens("emp_first_name", Map.of()))
                .containsExactly("emp", "first", "name");
        assertThat(ColumnNameSimilarity.expandedTokens("HIRE DATE", Map.of()))
                .containsExactly("hire", "date");
    }

    @Test
    void builtinAliasesExpandAbbreviations() {
        assertThat(ColumnNameSimilarity.expandedTokens("fname", Map.of()))
                .isEqualTo(Set.of("firstname"));
        assertThat(ColumnNameSimilarity.expandedTokens("DOB", Map.of()))
                .isEqualTo(Set.of("dateofbirth"));
        assertThat(ColumnNameSimilarity.expandedTokens("Surname", Map.of()))
                .isEqualTo(Set.of("lastname"));
    }

    @Test
    void aliasEqualityScoresNearPerfect() {
        assertThat(ColumnNameSimilarity.score("fname", "FirstName", Map.of())).isEqualTo(0.95);
        assertThat(ColumnNameSimilarity.score("Surname", "LastName", Map.of())).isEqualTo(0.95);
        assertThat(ColumnNameSimilarity.score("dob", "DateOfBirth", Map.of())).isEqualTo(0.95);
    }

    @Test
    void configuredExtraAliasesWin() {
        Map<String, String> extras = Map.of("matricule", "employeenumber");
        assertThat(ColumnNameSimilarity.score("Matricule", "EmployeeNumber", extras)).isEqualTo(0.95);
    }

    @Test
    void blendedScoreStaysWithinBoundsAndOrdersSensibly() {
        double related = ColumnNameSimilarity.score("employee first name", "FirstName", Map.of());
        double unrelated = ColumnNameSimilarity.score("Zebra_Quotient", "FirstName", Map.of());

        assertThat(related).isBetween(0.0, 1.0);
        assertThat(unrelated).isBetween(0.0, 1.0);
        assertThat(related).isGreaterThan(unrelated);
    }

    @Test
    void levenshteinRatioBounds() {
        assertThat(ColumnNameSimilarity.levenshteinRatio("abc", "abc")).isEqualTo(1.0);
        assertThat(ColumnNameSimilarity.levenshteinRatio("abc", "xyz")).isEqualTo(0.0);
    }
}
