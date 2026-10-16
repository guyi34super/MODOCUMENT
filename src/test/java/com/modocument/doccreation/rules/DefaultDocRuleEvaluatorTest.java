package com.modocument.doccreation.rules;

import com.modocument.doccreation.dto.ValidationResultDto;
import com.modocument.doccreation.dto.ValidationResultDto.Issue;
import com.modocument.doccreation.entity.DocFieldRule;
import com.modocument.doccreation.fields.FieldProps;
import com.modocument.doccreation.pdftemplate.RegionKind;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks of document-creation fields (field-validation-contract §3,
 * §4.2): every rule type, blank handling, relative dates on a fixed clock,
 * setup warnings instead of exceptions, and messages without values.
 */
class DefaultDocRuleEvaluatorTest {

    /** "Today" is 16 October 2026 (UTC). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);

    private final DefaultDocRuleEvaluator evaluator = new DefaultDocRuleEvaluator(CLOCK);
    private long nextId = 1;

    private static RegionView field(String key, String label, RegionKind kind) {
        return new RegionView(key, label, kind, null);
    }

    private static RegionView field(String key, String label, RegionKind kind, FieldProps props) {
        return new RegionView(key, label, kind, props);
    }

    private DocFieldRule rule(String key, String type, String config) {
        DocFieldRule rule = new DocFieldRule();
        rule.setDocFieldRuleId(nextId++);
        rule.setRegionKey(key);
        rule.setRuleType(type);
        rule.setConfigJson(config);
        return rule;
    }

    private ValidationResultDto run(List<RegionView> regions, Map<String, String> values, DocFieldRule... rules) {
        return evaluator.evaluate(regions, values, Set.of(), List.of(rules), 7);
    }

    /** One TEXT field "name" ("Full name") with {@code value}, one rule. */
    private ValidationResultDto one(String value, String type, String config) {
        Map<String, String> values = new HashMap<>();
        if (value != null) {
            values.put("name", value);
        }
        return run(List.of(field("name", "Full name", RegionKind.TEXT)), values, rule("name", type, config));
    }

    private static List<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).toList();
    }

    private static String onlyMessage(ValidationResultDto result) {
        assertThat(result.errors()).hasSize(1);
        return result.errors().get(0).message();
    }

    // ------------------------------------------------------------ the result

    @Test
    void aCleanDocumentIsValidatedAndTheRevisionIsEchoed() {
        ValidationResultDto result = one("Jane", "REQUIRED", "{}");

        assertThat(result.state()).isEqualTo("VALIDATED");
        assertThat(result.passed()).isTrue();
        assertThat(result.checked()).isTrue();
        assertThat(result.validatedAt()).isNull();
        assertThat(result.rulesRevision()).isEqualTo(7);
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.setupWarnings()).isEmpty();
    }

    @Test
    void anErrorKeepsADraftAndCarriesTheRuleAndLabel() {
        ValidationResultDto result = one("", "REQUIRED", "{}");

        assertThat(result.state()).isEqualTo("DRAFT");
        Issue issue = result.errors().get(0);
        assertThat(issue.regionKey()).isEqualTo("name");
        assertThat(issue.label()).isEqualTo("Full name");
        assertThat(issue.ruleId()).isEqualTo(1L);
        assertThat(issue.code()).isEqualTo("REQUIRED");
        assertThat(issue.message()).isEqualTo("Full name is required.");
    }

    @Test
    void aWarningDoesNotBlockAndACustomMessageWins() {
        DocFieldRule warning = rule("name", "LENGTH", "{\"Min\": 10}");
        warning.setSeverity("WARNING");
        DocFieldRule custom = rule("name", "REGEX", "{\"Pattern\": \"^[A-Z]\"}");
        custom.setMessage("  Start the name with a capital letter.  ");

        ValidationResultDto result = run(List.of(field("name", "Full name", RegionKind.TEXT)),
                Map.of("name", "jane"), warning, custom);

        assertThat(result.warnings()).extracting(Issue::code).containsExactly("LENGTH");
        assertThat(result.errors()).extracting(Issue::message)
                .containsExactly("Start the name with a capital letter.");

        ValidationResultDto onlyWarning = run(List.of(field("name", "Full name", RegionKind.TEXT)),
                Map.of("name", "jane"), warning);
        assertThat(onlyWarning.state()).isEqualTo("VALIDATED");
        assertThat(onlyWarning.warnings()).hasSize(1);
    }

    // ------------------------------------------------------------- built-ins

    @Test
    void builtInKindChecks() {
        List<RegionView> regions = List.of(
                field("dob", "Date of birth", RegionKind.DATE),
                field("amount", "Amount", RegionKind.NUMBER),
                field("agree", "I agree", RegionKind.CHECKBOX),
                field("code", "Code", RegionKind.TEXT, new FieldProps(null, null, null, null, null, null, 3)),
                field("photo", "Photo", RegionKind.IMAGE));
        ValidationResultDto bad = run(regions, Map.of("dob", "16/10/2026", "amount", "12abc", "agree", "yes",
                "code", "ABCD"));
        assertThat(codes(bad.errors())).containsExactly("DATE_INVALID", "NUMBER_INVALID", "CHECKBOX_INVALID",
                "TOO_LONG");
        assertThat(bad.errors()).allSatisfy(issue -> assertThat(issue.ruleId()).isNull());
        assertThat(bad.errors().get(3).message()).isEqualTo("Code must be at most 3 characters.");

        ValidationResultDto good = run(regions, Map.of("dob", "2026-02-28", "amount", "-1,234.50", "agree", "true",
                "code", "ABC"));
        assertThat(good.errors()).isEmpty();
        // Blank values pass every built-in check; so does a 29 February only in a leap year.
        assertThat(run(regions, Map.of()).errors()).isEmpty();
        assertThat(codes(run(regions, Map.of("dob", "2026-02-29")).errors())).containsExactly("DATE_INVALID");
        assertThat(run(regions, Map.of("dob", "2028-02-29")).errors()).isEmpty();
    }

    @Test
    void choiceValuesMustBeOneOfTheOptions() {
        // CHOICE arrives with BE-B's RegionKind change; it is referenced by name.
        Optional<RegionKind> choice = Arrays.stream(RegionKind.values())
                .filter(k -> k.name().equals("CHOICE")).findFirst();
        FieldProps options = new FieldProps(null, null, null, List.of("Single", "Married"), null, null, null);
        RegionKind kind = choice.orElse(RegionKind.TEXT);
        ValidationResultDto result = run(List.of(field("status", "Status", kind, options)),
                Map.of("status", "Divorced"));
        if (choice.isPresent()) {
            assertThat(codes(result.errors())).containsExactly("CHOICE_INVALID");
            assertThat(run(List.of(field("status", "Status", kind, options)), Map.of("status", "Married"))
                    .errors()).isEmpty();
        } else {
            assertThat(result.errors()).as("options only constrain CHOICE fields").isEmpty();
        }
    }

    @Test
    void aFailedBuiltInCheckSkipsTheFieldsRules() {
        ValidationResultDto result = run(List.of(field("dob", "Date of birth", RegionKind.DATE)),
                Map.of("dob", "not a date"), rule("dob", "DATE_RANGE", "{\"Max\": \"today\"}"),
                rule("dob", "REQUIRED", "{}"));

        assertThat(codes(result.errors())).containsExactly("DATE_INVALID");
    }

    // ------------------------------------------------------------- blanks

    @Test
    void everyRuleButRequiredAndRequiredIfPassesABlankValue() {
        Map<String, String> configs = Map.of(
                "REGEX", "{\"Pattern\": \"^x$\"}",
                "EMAIL", "{}",
                "LENGTH", "{\"Min\": 3}",
                "RANGE", "{\"Min\": 1}",
                "ALLOWED_VALUES", "{\"Values\": [\"a\"]}",
                "DATE_FORMAT", "{}",
                "DATE_RANGE", "{\"Min\": \"today\"}",
                "EXPIRY_DATE", "{}",
                "COMPARE", "{\"Field\": \"other\", \"Op\": \"=\"}");
        List<RegionView> regions = List.of(field("name", "Full name", RegionKind.TEXT),
                field("other", "Other", RegionKind.TEXT));
        for (DocRuleType type : DocRuleType.values()) {
            String config = configs.getOrDefault(type.name(), type == DocRuleType.REQUIRED_IF
                    ? "{\"Field\": \"other\"}" : "{}");
            for (String blank : new String[]{null, "", "   "}) {
                Map<String, String> values = new HashMap<>();
                values.put("other", "filled");
                if (blank != null) {
                    values.put("name", blank);
                }
                ValidationResultDto result = run(regions, values, rule("name", type.name(), config));
                assertThat(result.setupWarnings()).as(type.name()).isEmpty();
                assertThat(result.passed()).as(type + " on a blank value").isEqualTo(!type.checksBlank());
            }
        }
    }

    // ------------------------------------------------------------ rule types

    @Test
    void requiredOnTextPhotosAndCheckboxes() {
        List<RegionView> regions = List.of(field("photo", "Passport photo", RegionKind.IMAGE),
                field("sign", "Signature", RegionKind.SIGNATURE),
                field("agree", "I agree", RegionKind.CHECKBOX));
        DocFieldRule photo = rule("photo", "REQUIRED", "{}");
        DocFieldRule sign = rule("sign", "REQUIRED", "{}");
        DocFieldRule agree = rule("agree", "REQUIRED", "{}");

        ValidationResultDto none = evaluator.evaluate(regions, Map.of(), Set.of(), List.of(photo, sign, agree), 1);
        assertThat(none.errors()).extracting(Issue::message).containsExactly("Passport photo is required.",
                "Signature is required.", "I agree is required.");
        ValidationResultDto all = evaluator.evaluate(regions, Map.of("agree", "TRUE"), Set.of("photo", "sign"),
                List.of(photo, sign, agree), 1);
        assertThat(all.errors()).isEmpty();
        assertThat(one("  ", "REQUIRED", "{}").passed()).isFalse();
        assertThat(one("x", "required", "{}").passed()).as("type names are case-insensitive").isTrue();
    }

    @Test
    void regexIsAnUnanchoredFindAndATimeoutFails() {
        assertThat(one("ID A123-9", "REGEX", "{\"Pattern\": \"[A-Z]\\\\d{3}\"}").passed()).isTrue();
        assertThat(onlyMessage(one("abc", "REGEX", "{\"Pattern\": \"^\\\\d+$\"}")))
                .isEqualTo("Full name is not in the expected format.");
        // Catastrophic backtracking: the 200 ms deadline fails the value instead of hanging.
        long start = System.nanoTime();
        ValidationResultDto slow = one("a".repeat(40) + "!", "REGEX", "{\"Pattern\": \"^(a+)+$\"}");
        assertThat(slow.passed()).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void email() {
        assertThat(one("jane@example.mu", "EMAIL", "{}").passed()).isTrue();
        assertThat(onlyMessage(one("jane@example", "EMAIL", "{}")))
                .isEqualTo("Full name must be an e-mail address.");
        assertThat(one("jane doe@example.mu", "EMAIL", "{}").passed()).isFalse();
    }

    @Test
    void lengthCountsCodePointsOfTheTrimmedValue() {
        assertThat(one("  ab  ", "LENGTH", "{\"Min\": 2, \"Max\": 2}").passed()).isTrue();
        assertThat(one("😀😀", "LENGTH", "{\"Max\": 2}").passed()).as("two emoji").isTrue();
        assertThat(onlyMessage(one("a", "LENGTH", "{\"Min\": 2, \"Max\": 5}")))
                .isEqualTo("Full name must be between 2 and 5 characters.");
        assertThat(onlyMessage(one("a", "LENGTH", "{\"Min\": 2}")))
                .isEqualTo("Full name must be at least 2 characters.");
        assertThat(onlyMessage(one("abcdef", "LENGTH", "{\"Max\": \"5\"}")))
                .isEqualTo("Full name must be at most 5 characters.");
    }

    @Test
    void rangeReadsBigDecimalsWithoutCommas() {
        assertThat(one("1,000.50", "RANGE", "{\"Min\": 1000, \"Max\": 1000.5}").passed()).isTrue();
        assertThat(onlyMessage(one("1000.51", "RANGE", "{\"Min\": 1000, \"Max\": 1000.50}")))
                .isEqualTo("Full name must be a number between 1000 and 1000.5.");
        assertThat(onlyMessage(one("ten", "RANGE", "{\"Min\": 1}")))
                .isEqualTo("Full name must be a number of at least 1.");
        assertThat(onlyMessage(one("11", "RANGE", "{\"Max\": 10}")))
                .isEqualTo("Full name must be a number of at most 10.");
        assertThat(onlyMessage(one("ten", "RANGE", "{}"))).isEqualTo("Full name must be a number.");
    }

    @Test
    void allowedValuesListAtMostTen() {
        assertThat(one(" married ", "ALLOWED_VALUES", "{\"Values\": [\"Single\", \"Married\"], \"IgnoreCase\": true}")
                .passed()).isTrue();
        assertThat(onlyMessage(one("married", "ALLOWED_VALUES", "{\"Values\": [\"Single\", \"Married\"]}")))
                .isEqualTo("Full name must be one of: Single, Married.");
        String many = IntStream.rangeClosed(1, 12).mapToObj(i -> "\"v" + i + "\"")
                .reduce((a, b) -> a + "," + b).orElseThrow();
        assertThat(onlyMessage(one("x", "ALLOWED_VALUES", "{\"Values\": [" + many + "]}")))
                .isEqualTo("Full name must be one of: v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, ….");
    }

    @Test
    void dateFormatIsStrictAndDefaultsToIso() {
        assertThat(one("2026-12-31", "DATE_FORMAT", "{}").passed()).isTrue();
        assertThat(onlyMessage(one("31/12/2026", "DATE_FORMAT", "{}")))
                .isEqualTo("Full name must be a date like 2026-12-31.");
        String config = "{\"Formats\": [\"dd/MM/yyyy\", \"d MMM yyyy\"]}";
        assertThat(one("31/12/2026", "DATE_FORMAT", config).passed()).isTrue();
        assertThat(one("5 Jan 2026", "DATE_FORMAT", config).passed()).isTrue();
        assertThat(onlyMessage(one("31/02/2026", "DATE_FORMAT", config)))
                .as("strict: no 31 February").isEqualTo("Full name must be a date like 31/12/2026.");
    }

    @Test
    void dateRangeTakesIsoTodayAndRelativeBoundsFromTheClock() {
        RegionView dob = field("dob", "Date of birth", RegionKind.DATE);
        DocFieldRule adult = rule("dob", "DATE_RANGE", "{\"Max\": \"-18y\"}");
        assertThat(run(List.of(dob), Map.of("dob", "2008-10-16"), adult).passed()).isTrue();
        ValidationResultDto minor = run(List.of(dob), Map.of("dob", "2008-10-17"), adult);
        assertThat(onlyMessage(minor)).isEqualTo("Date of birth must be on or before 2008-10-16.");

        RegionView due = field("due", "Due date", RegionKind.DATE,
                new FieldProps(null, null, null, null, "dd/MM/yyyy", null, null));
        DocFieldRule window = rule("due", "DATE_RANGE", "{\"Min\": \"today\", \"Max\": \"+2w\"}");
        assertThat(run(List.of(due), Map.of("due", "2026-10-16"), window).passed()).isTrue();
        assertThat(run(List.of(due), Map.of("due", "2026-10-30"), window).passed()).isTrue();
        assertThat(onlyMessage(run(List.of(due), Map.of("due", "2026-10-31"), window)))
                .as("bounds are shown in the field's date format")
                .isEqualTo("Due date must be on or after 16/10/2026 and on or before 30/10/2026.");
        DocFieldRule months = rule("due", "DATE_RANGE", "{\"Min\": \"2026-01-01\", \"Max\": \"+1m\"}");
        assertThat(run(List.of(due), Map.of("due", "2026-11-16"), months).passed()).isTrue();
        assertThat(run(List.of(due), Map.of("due", "2026-11-17"), months).passed()).isFalse();
        DocFieldRule days = rule("due", "DATE_RANGE", "{\"Min\": \"-30d\"}");
        assertThat(onlyMessage(run(List.of(due), Map.of("due", "2026-09-15"), days)))
                .isEqualTo("Due date must be on or after 16/09/2026.");
    }

    @Test
    void expiryDateMustBeAfterToday() {
        RegionView expiry = field("expiry", "Passport expiry", RegionKind.DATE);
        DocFieldRule rule = rule("expiry", "EXPIRY_DATE", "{}");
        assertThat(run(List.of(expiry), Map.of("expiry", "2026-10-17"), rule).passed()).isTrue();
        assertThat(onlyMessage(run(List.of(expiry), Map.of("expiry", "2026-10-16"), rule)))
                .isEqualTo("Passport expiry must be a future date.");
    }

    @Test
    void requiredIfTheOtherFieldIsFilledOrEquals() {
        List<RegionView> regions = List.of(field("spouse", "Spouse name", RegionKind.TEXT),
                field("status", "Marital status", RegionKind.TEXT),
                field("photo", "Photo", RegionKind.IMAGE),
                field("consent", "Consent", RegionKind.CHECKBOX));
        DocFieldRule ifFilled = rule("spouse", "REQUIRED_IF", "{\"Field\": \"status\"}");
        DocFieldRule ifMarried = rule("spouse", "REQUIRED_IF", "{\"Field\": \"status\", \"Equals\": \"Married\"}");
        DocFieldRule ifPhoto = rule("consent", "REQUIRED_IF", "{\"Field\": \"photo\"}");

        assertThat(run(regions, Map.of(), ifFilled, ifMarried).passed()).isTrue();
        assertThat(onlyMessage(run(regions, Map.of("status", "Single"), ifFilled)))
                .isEqualTo("Spouse name is required when Marital status is filled.");
        assertThat(run(regions, Map.of("status", "Single"), ifMarried).passed()).isTrue();
        assertThat(onlyMessage(run(regions, Map.of("status", "  married "), ifMarried)))
                .isEqualTo("Spouse name is required when Marital status is Married.");
        assertThat(run(regions, Map.of("status", "Married", "spouse", "John"), ifMarried).passed()).isTrue();

        // A checkbox is filled when ticked; a photo field when it holds a photo.
        assertThat(evaluator.evaluate(regions, Map.of(), Set.of("photo"), List.of(ifPhoto), 1).passed()).isFalse();
        assertThat(evaluator.evaluate(regions, Map.of("consent", "true"), Set.of("photo"), List.of(ifPhoto), 1)
                .passed()).isTrue();
        assertThat(evaluator.evaluate(regions, Map.of(), Set.of(), List.of(ifPhoto), 1).passed()).isTrue();
    }

    @Test
    void compareDatesNumbersAndText() {
        List<RegionView> regions = List.of(field("start", "Start date", RegionKind.DATE),
                field("end", "End date", RegionKind.DATE),
                field("min", "Minimum", RegionKind.NUMBER),
                field("max", "Maximum", RegionKind.NUMBER),
                field("email", "E-mail", RegionKind.TEXT),
                field("confirm", "Confirm e-mail", RegionKind.TEXT));
        DocFieldRule after = rule("end", "COMPARE", "{\"Field\": \"start\", \"Op\": \">\"}");
        DocFieldRule atLeast = rule("max", "COMPARE", "{\"Field\": \"min\", \"Op\": \">=\"}");
        DocFieldRule same = rule("confirm", "COMPARE", "{\"Field\": \"email\", \"Op\": \"=\"}");
        DocFieldRule ordered = rule("confirm", "COMPARE", "{\"Field\": \"email\", \"Op\": \"<\"}");

        assertThat(run(regions, Map.of("start", "2026-01-01", "end", "2026-01-02"), after).passed()).isTrue();
        assertThat(onlyMessage(run(regions, Map.of("start", "2026-01-02", "end", "2026-01-02"), after)))
                .isEqualTo("End date must be after Start date.");
        // Numbers compare as numbers, not text ("9" < "10").
        assertThat(run(regions, Map.of("min", "9", "max", "10"), atLeast).passed()).isTrue();
        assertThat(onlyMessage(run(regions, Map.of("min", "1,000", "max", "999.99"), atLeast)))
                .isEqualTo("Maximum must be at least Minimum.");
        assertThat(run(regions, Map.of("email", "a@b.mu", "confirm", " a@b.mu "), same).passed()).isTrue();
        assertThat(onlyMessage(run(regions, Map.of("email", "a@b.mu", "confirm", "c@b.mu"), same)))
                .isEqualTo("Confirm e-mail must be the same as E-mail.");
        assertThat(run(regions, Map.of("email", "b", "confirm", "a"), ordered).passed())
                .as("text is only compared with = and !=").isTrue();
        assertThat(run(regions, Map.of("end", "2026-01-01"), after).passed())
                .as("skipped while the other field is blank").isTrue();
    }

    // -------------------------------------------------------- setup warnings

    @Test
    void rulesThatCannotRunAreSetupWarningsNotExceptions() {
        List<RegionView> regions = List.of(field("name", "Full name", RegionKind.TEXT),
                field("photo", "Passport photo", RegionKind.IMAGE),
                field("agree", "I agree", RegionKind.CHECKBOX));
        List<DocFieldRule> rules = new ArrayList<>(List.of(
                rule("gone", "REQUIRED", "{}"),
                rule("name", "SPELLCHECK", "{}"),
                rule("photo", "RANGE", "{\"Min\": 1}"),
                rule("agree", "LENGTH", "{\"Max\": 1}"),
                rule("name", "LENGTH", "{not json"),
                rule("name", "LENGTH", "[1, 2]"),
                rule("name", "REGEX", "{}"),
                rule("name", "REGEX", "{\"Pattern\": \"([a-\"}"),
                rule("name", "RANGE", "{\"Min\": \"lots\"}"),
                rule("name", "DATE_RANGE", "{\"Min\": \"next week\"}"),
                rule("name", "DATE_FORMAT", "{\"Formats\": [\"qqqq-bb\"]}"),
                rule("name", "ALLOWED_VALUES", "{\"Values\": []}"),
                rule("name", "COMPARE", "{\"Field\": \"photo\", \"Op\": \"~\"}"),
                rule("name", "COMPARE", "{\"Field\": \"nowhere\", \"Op\": \"=\"}"),
                rule("name", "REQUIRED_IF", "{\"Field\": \"name\"}"),
                rule("name", "REQUIRED_IF", "{}")));
        DocFieldRule nullConfig = rule("name", "REQUIRED", null);
        rules.add(nullConfig);

        ValidationResultDto result = evaluator.evaluate(regions, Map.of("name", "Jane", "agree", "true"),
                Set.of(), rules, 3);

        assertThat(result.errors()).isEmpty();
        assertThat(result.state()).isEqualTo("VALIDATED");
        assertThat(result.setupWarnings()).hasSize(rules.size() - 1);
        assertThat(result.setupWarnings()).extracting(ValidationResultDto.SetupWarning::message)
                .contains("RANGE does not apply to a photo field; skipped.",
                        "LENGTH does not apply to a checkbox field; skipped.",
                        "Rule type \"SPELLCHECK\" is not known; skipped.",
                        "REQUIRED is set for field \"gone\", which this version of the template does not have; "
                                + "skipped.",
                        "LENGTH on Full name has unreadable settings; skipped.",
                        "REGEX on Full name has no pattern; skipped.");
        assertThat(result.setupWarnings()).allSatisfy(w -> assertThat(w.ruleId()).isNotNull());
        assertThat(result.setupWarnings().stream().filter(w -> "gone".equals(w.regionKey()))).hasSize(1);
    }

    // ---------------------------------------------------- values never leak

    @Test
    void messagesNeverContainTheValue() {
        String secret = "Zq9-SECRET-831";
        List<RegionView> regions = List.of(field("name", "Full name", RegionKind.TEXT),
                field("other", "Other", RegionKind.TEXT),
                field("dob", "Date of birth", RegionKind.DATE),
                field("amount", "Amount", RegionKind.NUMBER),
                field("agree", "I agree", RegionKind.CHECKBOX),
                field("short", "Short", RegionKind.TEXT, new FieldProps(null, null, null, null, null, null, 2)));
        List<DocFieldRule> rules = List.of(
                rule("name", "REGEX", "{\"Pattern\": \"^\\\\d+$\"}"),
                rule("name", "EMAIL", "{}"),
                rule("name", "LENGTH", "{\"Max\": 3}"),
                rule("name", "RANGE", "{\"Min\": 1}"),
                rule("name", "ALLOWED_VALUES", "{\"Values\": [\"a\"]}"),
                rule("name", "DATE_FORMAT", "{}"),
                rule("name", "DATE_RANGE", "{\"Min\": \"today\"}"),
                rule("name", "EXPIRY_DATE", "{}"),
                rule("name", "COMPARE", "{\"Field\": \"other\", \"Op\": \"=\"}"));
        Map<String, String> values = Map.of("name", secret, "other", secret + "x", "dob", secret,
                "amount", secret, "agree", secret, "short", secret);

        ValidationResultDto result = evaluator.evaluate(regions, values, Set.of(), rules, 1);

        assertThat(result.errors()).hasSize(rules.size() + 4);
        assertThat(result.toString()).doesNotContain("SECRET");
    }
}
