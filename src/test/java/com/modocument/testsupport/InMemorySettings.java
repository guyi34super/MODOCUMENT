package com.modocument.testsupport;

import com.modocument.application.port.SettingsReader;
import com.modocument.domain.entity.BankSetting;
import com.modocument.infrastructure.settings.SettingsCache;
import com.modocument.infrastructure.settings.SettingsCatalogue;
import com.modocument.infrastructure.settings.SettingsResolver;
import com.modocument.infrastructure.settings.SettingsSnapshot;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The real ws20 settings resolution over an in-memory store, for unit tests
 * of settings CONSUMERS: the catalogue's shipped defaults come from the
 * shipped application.yml exactly as packaged (no environment variables),
 * optionally overlaid with test properties; stored values are BankSetting
 * rows kept here and turned into a fresh snapshot on every change — the
 * same parsing, clamping and inheritance as production, no database.
 */
public final class InMemorySettings {

    /** The platform bank of every fixture. */
    public static final int PLATFORM_BANK = 1;

    private final SettingsCatalogue catalogue;
    private final SettingsCache cache = mock(SettingsCache.class);
    private final SettingsResolver resolver;
    private final List<BankSetting> rows = new ArrayList<>();
    private long revision = 1;

    private InMemorySettings(Map<String, Object> overrides) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        try {
            List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            environment.getPropertySources().addFirst(yaml.get(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!overrides.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("test", overrides));
        }
        this.catalogue = new SettingsCatalogue(environment);
        this.resolver = new SettingsResolver(catalogue, cache);
        publish();
    }

    /** The shipped application.yml, nothing stored. */
    public static InMemorySettings shipped() {
        return new InMemorySettings(Map.of());
    }

    /** The shipped application.yml with some properties overridden (env-style), nothing stored. */
    public static InMemorySettings shippedWith(Map<String, Object> properties) {
        return new InMemorySettings(properties);
    }

    public SettingsReader reader() {
        return resolver;
    }

    public SettingsResolver resolver() {
        return resolver;
    }

    public SettingsCatalogue catalogue() {
        return catalogue;
    }

    /** Stores a bank's own override (replacing an earlier one). */
    public InMemorySettings bank(int bankId, String key, String value) {
        BankSetting row = row(bankId, key);
        row.setValueText(value);
        return publish();
    }

    /** Stores the platform owner's value (applies to every bank without its own). */
    public InMemorySettings platform(String key, String value) {
        return bank(PLATFORM_BANK, key, value);
    }

    /** Stores the platform's bank bounds of a ranged BANK setting. */
    public InMemorySettings platformBounds(String key, String bankMin, String bankMax) {
        BankSetting row = row(PLATFORM_BANK, key);
        row.setBankMinText(bankMin);
        row.setBankMaxText(bankMax);
        return publish();
    }

    /** Removes every stored value. */
    public InMemorySettings clear() {
        rows.clear();
        return publish();
    }

    private BankSetting row(int bankId, String key) {
        for (BankSetting row : rows) {
            if (row.getBankId() == bankId && row.getSettingKey().equals(key)) {
                row.setVersion(row.getVersion() + 1);
                return row;
            }
        }
        BankSetting row = new BankSetting(bankId, key);
        row.setVersion(1);
        rows.add(row);
        return row;
    }

    private InMemorySettings publish() {
        SettingsSnapshot snapshot = SettingsSnapshot.build(revision++,
                OptionalInt.of(PLATFORM_BANK), List.copyOf(rows), catalogue);
        when(cache.current()).thenReturn(snapshot);
        return this;
    }
}
