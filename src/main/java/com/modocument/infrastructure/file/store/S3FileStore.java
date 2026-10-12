package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link FileStore} on Amazon S3 (optimization ws29): uploads, exports and
 * doc assets survive ECS task replacement and are shared by every task.
 *
 * <ul>
 *   <li>Object key = optional {@code modocument.storage.s3.prefix} + the
 *       store key (same layout as the local disk).</li>
 *   <li>SSE-KMS: with {@code kms-key-id} set every PUT names that key; unset,
 *       the bucket's default encryption (Terraform: the same CMK) applies.</li>
 *   <li>{@link #localCopy} downloads into a bounded on-disk cache
 *       ({@link LocalCopyCache}) re-validated by ETag on every call, so a
 *       regenerated export is never served stale.</li>
 *   <li>Every SDK failure surfaces as an {@link IOException} (a missing key
 *       as {@link NoSuchFileException}), the same contract the local store
 *       has, so callers' error handling does not depend on the backend.</li>
 * </ul>
 */
public class S3FileStore implements FileStore, AutoCloseable {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final String kmsKeyId;
    private final LocalCopyCache cache;

    public S3FileStore(S3Client s3, String bucket, String prefix, String kmsKeyId, LocalCopyCache cache) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "modocument.storage.backend=s3 needs modocument.storage.s3.bucket "
                            + "(env MODOCUMENT_STORAGE_S3_BUCKET).");
        }
        this.s3 = s3;
        this.bucket = bucket.strip();
        this.prefix = normalizePrefix(prefix);
        this.kmsKeyId = kmsKeyId == null || kmsKeyId.isBlank() ? null : kmsKeyId.strip();
        this.cache = cache;
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : FileStoreKeys.validate(trimmed) + "/";
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        String objectKey = objectKey(key);
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentLength(length)
                .contentType(contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType);
        if (kmsKeyId != null) {
            request.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        }
        try {
            s3.putObject(request.build(), RequestBody.fromInputStream(content, length));
        } catch (SdkException e) {
            throw io("store", key, e);
        }
        cache.invalidate(key);
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                    .bucket(bucket).key(objectKey(key)).build());
            return in;
        } catch (SdkException e) {
            throw io("read", key, e);
        }
    }

    @Override
    public Path localCopy(String key) throws IOException {
        String objectKey = objectKey(key);
        HeadObjectResponse head = head(key, objectKey);
        if (head == null) {
            cache.invalidate(key);
            throw new NoSuchFileException(key);
        }
        return cache.get(key, head.eTag(), target -> {
            try (InputStream in = open(key)) {
                java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    @Override
    public boolean exists(String key) throws IOException {
        return head(key, objectKey(key)) != null;
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey(key)).build());
        } catch (SdkException e) {
            throw io("delete", key, e);
        }
        cache.invalidate(key);
    }

    @Override
    public List<String> list(String listPrefix) throws IOException {
        String safePrefix = FileStoreKeys.validatePrefix(listPrefix);
        List<String> keys = new ArrayList<>();
        try {
            for (S3Object object : s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix + safePrefix).build()).contents()) {
                keys.add(object.key().substring(prefix.length()));
            }
        } catch (SdkException e) {
            throw io("list", safePrefix, e);
        }
        keys.sort(null);
        return keys;
    }

    @Override
    public void close() {
        s3.close();
        cache.clear();
    }

    private HeadObjectResponse head(String key, String objectKey) throws IOException {
        try {
            return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw io("check", key, e);
        } catch (SdkException e) {
            throw io("check", key, e);
        }
    }

    private String objectKey(String key) {
        return prefix + FileStoreKeys.validate(key);
    }

    private static IOException io(String action, String key, SdkException e) {
        if (e instanceof NoSuchKeyException
                || (e instanceof S3Exception s3e && s3e.statusCode() == 404)) {
            NoSuchFileException missing = new NoSuchFileException(key);
            missing.initCause(e);
            return missing;
        }
        return new IOException("S3 could not " + action + " '" + key + "': " + e.getMessage(), e);
    }
}
