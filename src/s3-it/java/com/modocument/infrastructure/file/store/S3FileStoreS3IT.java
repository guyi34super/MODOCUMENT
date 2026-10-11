package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FileStore contract against a real S3 API (ws29): S3FileStore on a
 * Testcontainers Adobe S3Mock (an S3-compatible server; the MinIO images
 * left Docker Hub). Run with {@code mvn -B verify -Ps3-it -DskipUTs=true}
 * (needs Docker — CI's storage-s3 job). Each test gets its own key prefix,
 * so the contract's list assertions see only their own objects.
 */
@Testcontainers
class S3FileStoreS3IT extends FileStoreContract {

    /** A pinned S3Mock release; port 9090 is its plain-HTTP S3 endpoint. */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withExposedPorts(9090)
            .waitingFor(Wait.forListeningPort());

    private static final String BUCKET = "modocument-files-it";
    private static S3Client client;

    @TempDir
    Path cacheDir;

    private LocalCopyCache cache;
    private String prefix;
    private S3FileStore store;

    @BeforeAll
    static void createBucket() {
        client = S3Client.builder()
                .endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090)))
                .forcePathStyle(true)
                .region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("it-access-key", "it-secret-key")))
                .httpClientBuilder(ApacheHttpClient.builder())
                // S3-compatible endpoint: checksums only where the API requires
                // them (what FileStoreConfiguration does for an endpoint override).
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        cache = new LocalCopyCache(cacheDir, 1024 * 1024, Duration.ofMinutes(1), Clock.systemUTC());
        prefix = "it-" + UUID.randomUUID();
        // kms-key-id empty: S3Mock has no KMS; the request shaping for SSE-KMS
        // is covered by S3FileStoreTest.
        store = new S3FileStore(client, BUCKET, prefix, null, cache);
    }

    @AfterEach
    void tearDown() {
        cache.clear();
    }

    @Override
    protected FileStore store() {
        return store;
    }

    @Test
    void objectsLandUnderThePrefixWithTheSameRelativeLayout() throws IOException {
        put(store, "exports/u3/job9.csv", "a");

        assertThat(store.list("exports/")).containsExactly("exports/u3/job9.csv");
        String objectKey = prefix + "/exports/u3/job9.csv";
        assertThat(client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(objectKey).build())
                .contentLength()).isEqualTo(1L);
    }

    @Test
    void aLocalCopyFollowsAnOverwriteFromAnotherTask() throws IOException {
        put(store, "exports/doccreation-instance3.pdf", "v1");
        Path first = store.localCopy("exports/doccreation-instance3.pdf");

        // A second ECS task (its own store + cache) regenerates the export.
        LocalCopyCache otherCache = new LocalCopyCache(cacheDir.resolve("other"), 1024 * 1024,
                Duration.ofMinutes(1), Clock.systemUTC());
        try {
            S3FileStore otherTask = new S3FileStore(client, BUCKET, prefix, null, otherCache);
            put(otherTask, "exports/doccreation-instance3.pdf", "v2 from another task");
        } finally {
            otherCache.clear();
        }

        Path second = store.localCopy("exports/doccreation-instance3.pdf");
        assertThat(Files.readString(second)).isEqualTo("v2 from another task");
        assertThat(second).isEqualTo(first);
    }
}
