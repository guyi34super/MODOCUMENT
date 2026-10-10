package com.modocument.infrastructure.settings;

import com.modocument.domain.model.settings.SettingDefinition;
import com.modocument.domain.model.settings.SettingType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/settings/catalogue.md carries a table GENERATED from
 * {@link SettingDefinitions} (between the BEGIN/END markers); this test
 * fails when the two drift. Regenerate after a catalogue change with
 * <pre>mvn -Dtest=SettingsCatalogueDocTest -Dmodocument.contract.update=true test</pre>
 */
class SettingsCatalogueDocTest {

    static final Path DOC = Path.of("docs", "settings", "catalogue.md");
    static final String BEGIN = "<!-- BEGIN GENERATED: SettingsCatalogueDocTest -->";
    static final String END = "<!-- END GENERATED -->";

    @Test
    void theCatalogueDocumentMatchesTheCode() throws IOException {
        String document = Files.readString(DOC, StandardCharsets.UTF_8);
        int begin = document.indexOf(BEGIN);
        int end = document.indexOf(END);
        assertThat(begin).as("BEGIN marker in " + DOC).isNotNegative();
        assertThat(end).as("END marker in " + DOC).isGreaterThan(begin);
        String rendered = render();
        String current = document.substring(begin + BEGIN.length(), end);
        if (Boolean.getBoolean("modocument.contract.update")) {
            Files.writeString(DOC, document.substring(0, begin + BEGIN.length()) + rendered
                    + document.substring(end), StandardCharsets.UTF_8);
            return;
        }
        assertThat(current)
                .as("docs/settings/catalogue.md is stale: run mvn -Dtest=SettingsCatalogueDocTest "
                        + "-Dmodocument.contract.update=true test")
                .isEqualTo(rendered);
    }

    static String render() {
        StringBuilder table = new StringBuilder("\n");
        table.append("| Key | Tab | Scope | Tier | Type | Default | Limits | Locked | Restart "
                + "| Property (shipped default) |\n");
        table.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (SettingDefinition definition : SettingDefinitions.ALL) {
            table.append("| `").append(definition.key()).append("` | ")
                    .append(definition.group().label()).append(" | ")
                    .append(definition.scope()).append(" | ")
                    .append(definition.tier()).append(" | ")
                    .append(definition.type()).append(" | ")
                    .append(definition.format(definition.codeDefault()))
                    .append(definition.unit() == null ? "" : " " + definition.unit())
                    .append(" | ")
                    .append(limits(definition)).append(" | ")
                    .append(definition.locked() ? "**locked**" : "").append(" | ")
                    .append(definition.requiresRestart() ? "yes" : "").append(" | ")
                    .append(definition.propertyName() == null
                            ? "— (code default; consumer lands later)"
                            : "`" + definition.propertyName() + "`")
                    .append(" |\n");
        }
        return table.toString();
    }

    private static String limits(SettingDefinition definition) {
        if (definition.type() == SettingType.BOOLEAN || definition.type() == SettingType.ENUM) {
            return String.join(", ", definition.allowedValues());
        }
        return definition.format(definition.hardMin()) + " – "
                + definition.format(definition.hardMax());
    }
}
