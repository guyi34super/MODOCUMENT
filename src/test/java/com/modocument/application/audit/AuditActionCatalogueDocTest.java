package com.modocument.application.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit action catalogue ({@link AuditActions}, served by
 * {@code GET /api/admin/audit/actions}) and docs/security/audit-events.md
 * list the same actions; every action an alert rule watches, and every
 * literal action passed to the recorder in {@code src/main/java}, is in the
 * catalogue.
 */
class AuditActionCatalogueDocTest {

    private static final Path DOC = Path.of("docs", "security", "audit-events.md");
    private static final Pattern DOC_ROW = Pattern.compile("^\\| `([A-Z][A-Z0-9_]+)` \\| ", Pattern.MULTILINE);
    private static final Pattern LITERAL_CALL = Pattern.compile(
            "(?:builder|\\.success|\\.denied|\\.failed)\\(\\s*\"([A-Z][A-Z0-9_]+)\"");

    private static Set<String> catalogue() {
        return AuditActions.all().stream().map(AuditActions.Action::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    void theDocListsExactlyTheCatalogue() throws IOException {
        Set<String> documented = new LinkedHashSet<>();
        Matcher row = DOC_ROW.matcher(Files.readString(DOC));
        while (row.find()) {
            documented.add(row.group(1));
        }
        assertThat(documented).containsExactlyInAnyOrderElementsOf(catalogue());
        assertThat(Files.readString(DOC)).contains("access-denied-burst")
                .contains("audit.readAuditEnabled");
    }

    @Test
    void everyActionHasACategoryAndTheWs28ActionsAreThere() {
        assertThat(AuditActions.all()).allSatisfy(a -> assertThat(a.category()).isNotBlank());
        assertThat(catalogue()).contains("TEMPLATE_CREATED", "TEMPLATE_FIELD_CREATED",
                "TEMPLATE_FIELD_UPDATED", "TEMPLATE_FIELD_DELETED", "VALIDATION_RULE_CREATED",
                "VALIDATION_RULE_UPDATED", "VALIDATION_RULE_DELETED", "FILE_UPLOADED",
                "INGESTION_JOB_CREATED", "INGESTION_REMAPPED", "INGESTION_ROWS_EDITED",
                "DOC_GENERATED", "DOC_DOWNLOADED", "EXPORT_DOWNLOADED", "TOKEN_REFRESHED",
                "ACCESS_DENIED", "SENSITIVE_DATA_VIEWED", "AUDIT_LOG_VIEWED",
                "AUDIT_LOG_EXPORTED");
    }

    @Test
    void everyAlertedActionIsCatalogued() throws IOException {
        String yml = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        Set<String> alerted = new LinkedHashSet<>();
        Matcher actions = Pattern.compile("actions: \\[([^\\]]*)]").matcher(yml);
        while (actions.find()) {
            for (String action : actions.group(1).split(",")) {
                String name = action.trim().replace("\"", "");
                if (!name.equals("*") && !name.isEmpty()) {
                    alerted.add(name);
                }
            }
        }
        assertThat(alerted).isNotEmpty();
        assertThat(catalogue()).containsAll(alerted);
    }

    @Test
    void everyLiteralActionInTheCodeIsCatalogued() throws IOException {
        Set<String> literals = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher call = LITERAL_CALL.matcher(Files.readString(file));
                while (call.find()) {
                    literals.add(call.group(1));
                }
            }
        }
        assertThat(literals).contains("LOGIN_SUCCESS", "DSR_SEARCH");
        assertThat(catalogue()).containsAll(literals);
    }
}
