package io.github.vihuynh72.brownie.storage.s3;

import io.github.vihuynh72.brownie.core.artifact.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Stages on disk so a failed or oversized input never replaces a durable object. */
public final class S3BlobStore implements BlobStore {
    final S3Client client;
    final String bucket;

    public S3BlobStore(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public UploadResult writeAndDigest(String key, InputStream content, long maxBytes) throws IOException {
        return write(key, content, maxBytes, false);
    }

    @Override
    public UploadResult writeNewAndDigest(String key, InputStream content, long maxBytes) throws IOException {
        return write(key, content, maxBytes, true);
    }

    private UploadResult write(String key, InputStream content, long maxBytes, boolean createOnly) throws IOException {
        Path staged = Files.createTempFile("brownie-upload-", ".bin");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = Files.newOutputStream(staged)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = content.read(buffer)) != -1) {
                    if (count > maxBytes - total) throw new BlobSizeLimitExceededException(maxBytes);
                    total += count;
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }
            var request = PutObjectRequest.builder().bucket(bucket).key(key);
            if (createOnly) request.ifNoneMatch("*");
            try {
                client.putObject(request.build(), RequestBody.fromFile(staged));
            } catch (S3Exception e) {
                if (createOnly && e.statusCode() == 412) {
                    throw new BlobAlreadyExistsException(key, e);
                }
                throw unavailable(e);
            }
            return new UploadResult(total, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            throw unavailable(e);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    @Override
    public Optional<Long> sizeOf(String key) throws IOException {
        try {
            return Optional.of(client.headObject(r -> r.bucket(bucket).key(key)).contentLength());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw unavailable(e);
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public InputStream openStream(String key) throws IOException {
        try {
            return client.getObject(r -> r.bucket(bucket).key(key));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.deleteObject(r -> r.bucket(bucket).key(key));
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private static IOException unavailable(RuntimeException cause) {
        return new BlobStoreUnavailableException("Object storage could not be reached.", cause);
    }
}
