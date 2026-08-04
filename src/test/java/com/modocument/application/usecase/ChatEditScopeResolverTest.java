package com.modocument.application.usecase;

import com.modocument.application.dto.RowValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEditScopeResolverTest {

    private static final List<String> COLUMNS = List.of("FirstName", "Email", "Department");
    private static final List<RowValidationResult> VALIDATION = List.of(
            new RowValidationResult(1, "FirstName", "ada", null, null),
            new RowValidationResult(2, "FirstName", "", "Value is required.", "REQUIRED"),
            new RowValidationResult(2, "Email", "bad@", "Email format is invalid.", "REGEX"),
            new RowValidationResult(3, "Email", "x", "Email format is invalid.", "REGEX"));

    @Test
    void bulkInstructionResolvesToAllErrors() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Fill empty required fields", 2, "FirstName", COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.ALL_ERRORS);
    }

    @Test
    void columnNameInInstructionResolvesToColumn() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Trim whitespace in Email column", 2, "FirstName", COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.COLUMN);
        assertThat(resolution.columns()).containsExactly("Email");
    }

    @Test
    void nameColumnsPhraseMatchesNameLikeColumns() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Fix name columns", null, null, COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.COLUMN);
        assertThat(resolution.columns()).contains("FirstName");
    }

    @Test
    void rowNumberInInstructionResolvesToRow() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Fix row 2", null, null, COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.ROW);
        assertThat(resolution.row()).isEqualTo(2);
    }

    @Test
    void thisRowWithHintResolvesToRow() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Fix this row", 2, "Email", COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.ROW);
        assertThat(resolution.row()).isEqualTo(2);
    }

    @Test
    void vagueFixWithRowAndColumnHintsResolvesToCell() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Fix it", 2, "Email", COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.CELL);
        assertThat(resolution.row()).isEqualTo(2);
        assertThat(resolution.column()).isEqualTo("Email");
    }

    @Test
    void rowHintWithoutColumnSignalDefaultsToRow() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Make it valid", 2, null, COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.ROW);
        assertThat(resolution.row()).isEqualTo(2);
    }

    @Test
    void noHintsFallsBackToAllErrors() {
        ChatEditScopeResolution resolution = ChatEditScopeResolver.resolve(
                "Fix the validation errors", null, null, COLUMNS, VALIDATION);

        assertThat(resolution.scope()).isEqualTo(ChatEditScope.ALL_ERRORS);
    }

    @Test
    void isBulkInstructionTrueForBulkPhrasing() {
        assertThat(ChatEditScopeResolver.isBulkInstruction("Uppercase all first names")).isTrue();
        assertThat(ChatEditScopeResolver.isBulkInstruction("Fix every row")).isTrue();
        assertThat(ChatEditScopeResolver.isBulkInstruction("Fill empty required fields")).isTrue();
        assertThat(ChatEditScopeResolver.isBulkInstruction("Fix all the errors")).isTrue();
    }

    @Test
    void isBulkInstructionFalseForTargetedOrMissing() {
        assertThat(ChatEditScopeResolver.isBulkInstruction("Capitalize the first name")).isFalse();
        assertThat(ChatEditScopeResolver.isBulkInstruction("Tidy up the values")).isFalse();
        assertThat(ChatEditScopeResolver.isBulkInstruction("")).isFalse();
        assertThat(ChatEditScopeResolver.isBulkInstruction(null)).isFalse();
    }
}
