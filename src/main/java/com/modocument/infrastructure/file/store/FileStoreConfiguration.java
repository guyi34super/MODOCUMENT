package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import com.modocument.infrastructure.file.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/**
 * Picks the {@link FileStore} from {@code modocument.storage.backend}
 * (env MODOCUMENT_STORAGE_BACKEND): {@code local} (default — disk under
 * {@code modocument.storage.root}) or {@code s3} (bucket
 * {@code modocument.storage.s3.bucket}, credentials from the AWS default
 * chain — the ECS task role in production). An unknown value fails startup
 * with the accepted values spelled out instead of silently falling back to
 * the container disk.
 */
@Configuration
public class FileStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FileStoreConfiguration.class);

    @Bean
    public FileStore fileStore(StorageProperties storage,
                               @Value("${modocument.storage.backend:local}") String backend,
                               @Value("${modocument.storage.s3.bucket:}") String bucket,
                               @Value("${modocument.storage.s3.prefix:}") String prefix,
                               @Value("${modocument.storage.s3.region:}") String region,
                               @Value("${modocument.storage.s3.kms-key-id:}") String kmsKeyId,
                               @Value("${modocument.storage.s3.temp-cache-mb:512}") long tempCacheMb,
                               @Value("${modocument.storage.s3.endpoint:}") String endpoint,
                               @Value("${modocument.storage.s3.path-style:false}") boolean pathStyle) {
        String normalized = backend == null ? "local" : backend.strip().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "", "local" -> {
                log.info("File store: local disk under {}", storage.root().toAbsolutePath());
                return new LocalFileStore(storage.root());
            }
            case "s3" -> {
                S3Client client = s3Client(region, endpoint, pathStyle);
                try {
                    S3FileStore store = new S3FileStore(client, bucket, prefix, kmsKeyId,
                            LocalCopyCache.inTempDir(tempCacheMb));
                    log.info("File store: S3 bucket {} (prefix '{}', SSE-KMS key {}, local-copy cache {} MiB)",
                            bucket, prefix, kmsKeyId == null || kmsKeyId.isBlank() ? "bucket default" : "configured",
                            tempCacheMb);
                    return store;
                } catch (IOException e) {
                    client.close();
                    throw new UncheckedIOException("Could not create the S3 local-copy cache.", e);
                } catch (RuntimeException e) {
                    client.close();
                    throw e;
                }
            }
            default -> throw new IllegalStateException("modocument.storage.backend must be 'local' or 's3', not '"
                    + backend + "'.");
        }
    }

    static S3Client s3Client(String region, String endpoint, boolean pathStyle) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(50)
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(60)));
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.strip()));
        }
        if (endpoint != null && !endpoint.isBlank()) {
            // An S3-compatible store (MinIO, S3Mock): checksums only where the S3 API
            // requires them — not every implementation accepts the SDK's
            // default trailing CRC checksums.
            builder.endpointOverride(URI.create(endpoint.strip()))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        if (pathStyle) {
            builder.forcePathStyle(true);
        }
        return builder.build();
    }
}
