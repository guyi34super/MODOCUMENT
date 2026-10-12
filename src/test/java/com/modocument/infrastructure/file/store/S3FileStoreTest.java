package com.modocument.infrastructure.file.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S3FileStore's request shaping and error translation against a mocked SDK
 * client (the default suite has no Docker). The behaviour contract against a
 * real S3 API runs in {@code S3FileStoreS3IT} (-Ps3-it, S3Mock).
 */
class S3FileStoreTest {

    @TempDir
    Path tmp;

    private final S3Client s3 = mock(S3Client.class);
    private LocalCopyCache cache;
    private S3FileStore store;

    @BeforeEach
    void setUp() throws IOException {
        cache = new LocalCopyCache(tmp, 1024 * 1024, Duration.ofMinutes(1), Clock.systemUTC());
        store = new S3FileStore(s3, "modocument-files", "prod", "arn:aws:kms:eu-west-1:1:key/k", cache);
    }

    @AfterEach
    void tearDown() {
        cache.clear();
    }

    @Test
    void putNamesTheBucketPrefixedKeyAndTheKmsKey() throws IOException {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        store.put("uploads/a.csv", new ByteArrayInputStream(bytes), bytes.length, "text/csv");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("modocument-files");
        assertThat(request.getValue().key()).isEqualTo("prod/uploads/a.csv");
        assertThat(request.getValue().contentLength()).isEqualTo(5L);
        assertThat(request.getValue().contentType()).isEqualTo("text/csv");
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(request.getValue().ssekmsKeyId()).isEqualTo("arn:aws:kms:eu-west-1:1:key/k");
    }

    @Test
    void withoutAKmsKeyTheBucketDefaultEncryptionApplies() throws IOException {
        S3FileStore plain = new S3FileStore(s3, "b", null, " ", cache);
        plain.put("uploads/a.csv", new ByteArrayInputStream(new byte[1]), 1, null);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).isEqualTo("uploads/a.csv");
        assertThat(request.getValue().serverSideEncryption()).isNull();
        assertThat(request.getValue().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void aMissingKeyIsNoSuchFileEverywhere() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() -> store.open("uploads/x")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> store.localCopy("uploads/x")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void sdkFailuresSurfaceAsIoExceptions() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(SdkClientException.create("timeout"));

        assertThatThrownBy(() -> store.exists("uploads/x")).isInstanceOf(IOException.class)
                .isNotInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> store.delete("uploads/x")).isInstanceOf(IOException.class);
    }

    @Test
    void localCopyDownloadsOncePerEtag() throws IOException {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().eTag("\"e1\"").build())
                .thenReturn(HeadObjectResponse.builder().eTag("\"e1\"").build())
                .thenReturn(HeadObjectResponse.builder().eTag("\"e2\"").build());
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenReturn(object("v1"))
                .thenReturn(object("v2"));

        Path first = store.localCopy("exports/doccreation-instance1.docx");
        Path second = store.localCopy("exports/doccreation-instance1.docx");
        Path third = store.localCopy("exports/doccreation-instance1.docx");

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(third)).isEqualTo("v2");
        assertThat(third.getFileName().toString()).isEqualTo("doccreation-instance1.docx");
        verify(s3, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void unsafeKeysNeverReachS3() {
        assertThatThrownBy(() -> store.open("../other-tenant/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete("/abs")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void aBucketIsRequired() {
        assertThatThrownBy(() -> new S3FileStore(s3, " ", null, null, cache))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MODOCUMENT_STORAGE_S3_BUCKET");
    }

    private static ResponseInputStream<GetObjectResponse> object(String content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
    }
}
