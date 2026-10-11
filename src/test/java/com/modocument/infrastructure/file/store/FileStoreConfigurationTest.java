package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStoreConfigurationTest {

    @TempDir
    Path root;

    private FileStore create(String backend, String bucket) {
        return new FileStoreConfiguration().fileStore(new StorageProperties(root.toString()), backend,
                bucket, "", "eu-west-1", "", 16, "", false);
    }

    @Test
    void localIsTheDefaultBackend() {
        assertThat(create("local", "")).isInstanceOf(LocalFileStore.class);
        assertThat(create(" LOCAL ", "")).isInstanceOf(LocalFileStore.class);
        assertThat(((LocalFileStore) create("", "")).root()).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    void s3NeedsABucket() {
        assertThatThrownBy(() -> create("s3", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("modocument.storage.s3.bucket");
    }

    @Test
    void s3WithABucketBuildsTheS3Store() throws Exception {
        FileStore store = create("s3", "modocument-staging-files");
        try {
            assertThat(store).isInstanceOf(S3FileStore.class);
        } finally {
            ((S3FileStore) store).close();
        }
    }

    @Test
    void anUnknownBackendFailsInsteadOfFallingBackToDisk() {
        assertThatThrownBy(() -> create("gcs", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'local' or 's3'");
    }
}
