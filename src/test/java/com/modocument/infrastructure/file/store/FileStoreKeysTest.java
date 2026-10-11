package com.modocument.infrastructure.file.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStoreKeysTest {

    @Test
    void acceptsTheRelativeLayoutKeys() {
        for (String key : new String[] {"uploads/report_20261011120000.csv", "exports/u3/job9.xlsx",
                "exports/doccreation-instance12.pdf", "uploads/..hidden_20261011120000.txt",
                "uploads/name with spaces.pdf", "uploads/a:b.txt"}) {
            assertThat(FileStoreKeys.validate(key)).isEqualTo(key);
        }
    }

    @Test
    void rejectsAbsoluteTraversalAndBackslashKeys() {
        for (String key : new String[] {null, "", "  ", "/uploads/a.csv", "/etc/passwd",
                "C:/Windows/win.ini", "c:evil", "..", "../secrets.yml", "uploads/../../etc/passwd",
                "uploads/..", "uploads/./a.csv", "./uploads/a.csv", "uploads//a.csv", "uploads/",
                "uploads\\a.csv", "..\\..\\windows", "uploads/a\0.csv", "x".repeat(1025)}) {
            assertThatThrownBy(() -> FileStoreKeys.validate(key))
                    .as("key %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void namedKeysTakeOneSegment() {
        assertThat(FileStoreKeys.upload("a_20261011120000.csv")).isEqualTo("uploads/a_20261011120000.csv");
        assertThat(FileStoreKeys.export("doccreation-instance4.docx")).isEqualTo("exports/doccreation-instance4.docx");
        assertThat(FileStoreKeys.exportCopy(3, 9, ".csv")).isEqualTo("exports/u3/job9.csv");
        for (String name : new String[] {"../x", "a/b.csv", "..", "a\\b.csv", "", null}) {
            assertThatThrownBy(() -> FileStoreKeys.upload(name)).as("upload %s", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(FileStoreKeys.tryUpload("legacy\\name.csv")).isEmpty();
        assertThat(FileStoreKeys.tryExport("doccreation-instance4.pdf")).contains("exports/doccreation-instance4.pdf");
    }

    @Test
    void prefixesMayEndWithASlash() {
        assertThat(FileStoreKeys.validatePrefix("")).isEmpty();
        assertThat(FileStoreKeys.validatePrefix(null)).isEmpty();
        assertThat(FileStoreKeys.validatePrefix("exports/u3/")).isEqualTo("exports/u3/");
        assertThatThrownBy(() -> FileStoreKeys.validatePrefix("../")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileStoreKeys.validatePrefix("/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void s3PrefixIsNormalised() {
        assertThat(S3FileStore.normalizePrefix(null)).isEmpty();
        assertThat(S3FileStore.normalizePrefix(" ")).isEmpty();
        assertThat(S3FileStore.normalizePrefix("prod")).isEqualTo("prod/");
        assertThat(S3FileStore.normalizePrefix("prod/files//")).isEqualTo("prod/files/");
        assertThatThrownBy(() -> S3FileStore.normalizePrefix("../other")).isInstanceOf(IllegalArgumentException.class);
    }
}
