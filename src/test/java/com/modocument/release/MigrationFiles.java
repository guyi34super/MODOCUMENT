package com.modocument.release;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The Flyway migration folder as the ws24 release tests see it. */
final class MigrationFiles {

    static final Path FOLDER = Path.of("src", "main", "resources", "db", "migration");

    /** Flyway's versioned-migration file name: V&lt;version&gt;__&lt;description&gt;.sql. */
    static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__(.+)\\.sql$");

    /** One versioned migration. */
    record Migration(Path path, String fileName, String version, int major, String description) {

        String sql() {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private MigrationFiles() {
    }

    /** Every file name in the folder (not recursive), sorted. */
    static List<String> fileNames() {
        try (Stream<Path> files = Files.list(FOLDER)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every V*.sql migration, ordered by version. */
    static List<Migration> versioned() {
        return fileNames().stream()
                .map(name -> {
                    Matcher m = VERSIONED.matcher(name);
                    if (!m.matches()) {
                        return null;
                    }
                    String version = normalizeVersion(m.group(1));
                    int major = Integer.parseInt(version.split("\\.")[0]);
                    return new Migration(FOLDER.resolve(name), name, version, major, m.group(2));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(Migration::version, MigrationFiles::compareVersions))
                .toList();
    }

    /** Flyway's own normalisation: '_' and '.' separate parts, leading zeros do not count. */
    static String normalizeVersion(String raw) {
        return Arrays.stream(raw.split("[._]"))
                .map(part -> part.replaceFirst("^0+(?=\\d)", ""))
                .collect(Collectors.joining("."));
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long va = i < pa.length ? Long.parseLong(pa[i]) : 0;
            long vb = i < pb.length ? Long.parseLong(pb[i]) : 0;
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
        return 0;
    }
}
