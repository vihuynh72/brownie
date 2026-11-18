package io.github.vihuynh72.brownie.storage.s3;

import io.github.vihuynh72.brownie.core.artifact.*;
import io.github.vihuynh72.brownie.core.retention.*;
import io.github.vihuynh72.brownie.storage.azure.AzureDeletionLedgerArchive;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class S3StorageTest {
    private final Map<String, byte[]> objects = new HashMap<>();
    private int puts;
    private int putFailure;
    private Set<Path> existingStages;
    private Path observedStage;
    private final S3Client client = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{S3Client.class}, (proxy, method, args) -> {
                if (method.isDefault() && (method.getName().endsWith("Paginator")
                        || (args != null && args.length > 0 && args[0] instanceof java.util.function.Consumer)))
                    return InvocationHandler.invokeDefault(proxy, method, args);
                return switch (method.getName()) {
                    case "putObject" -> {
                        PutObjectRequest request = (PutObjectRequest) args[0];
                        puts++;
                        if (putFailure != 0) throw S3Exception.builder().statusCode(putFailure).build();
                        if (existingStages != null) {
                            try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                                observedStage = paths.filter(p -> p.getFileName().toString().startsWith("brownie-upload-"))
                                        .filter(p -> !existingStages.contains(p)).findFirst().orElseThrow();
                            }
                            assertEquals(1_000_000, Files.size(observedStage));
                        }
                        if ("*".equals(request.ifNoneMatch()) && objects.containsKey(request.key()))
                            throw S3Exception.builder().statusCode(412).build();
                        try (InputStream input = ((RequestBody) args[1]).contentStreamProvider().newStream()) {
                            objects.put(request.key(), input.readAllBytes());
                        }
                        yield PutObjectResponse.builder().build();
                    }
                    case "getObject" -> {
                        GetObjectRequest request = (GetObjectRequest) args[0];
                        byte[] bytes = objects.get(request.key());
                        if (bytes == null) throw S3Exception.builder().statusCode(404).build();
                        yield new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) bytes.length).build(),
                                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
                    }
                    case "listObjectsV2" -> ListObjectsV2Response.builder().contents(objects.keySet().stream()
                            .map(key -> S3Object.builder().key(key).build()).toList()).isTruncated(false).build();
                    case "serviceName" -> "s3";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.toString());
                };
            });
    private final S3BlobStore store = new S3BlobStore(client, "files");

    @Test
    void exactLimitIsAcceptedButOversizeAndFailedReadsNeverPutOrReplace() throws Exception {
        byte[] original = "original".getBytes();
        store.writeAndDigest("key", new ByteArrayInputStream(original), original.length);
        assertEquals(1, puts);
        assertThrows(BlobSizeLimitExceededException.class,
                () -> store.writeAndDigest("key", new ByteArrayInputStream("too long".getBytes()), 7));
        InputStream broken = new InputStream() {
            int count;
            @Override public int read() throws IOException {
                if (count++ == 2) throw new IOException("read failed");
                return 42;
            }
        };
        assertThrows(IOException.class, () -> store.writeAndDigest("key", broken, 10));
        assertEquals(1, puts);
        assertArrayEquals(original, objects.get("key"));
    }

    @Test
    void createOnlyUsesConditionalPutAndPreservesExistingObject() throws Exception {
        store.writeNewAndDigest("key", new ByteArrayInputStream(new byte[]{1}), 1);
        assertThrows(BlobAlreadyExistsException.class,
                () -> store.writeNewAndDigest("key", new ByteArrayInputStream(new byte[]{2}), 1));
        assertArrayEquals(new byte[]{1}, objects.get("key"));
        putFailure = 409;
        assertThrows(BlobStoreUnavailableException.class,
                () -> store.writeNewAndDigest("other", new ByteArrayInputStream(new byte[]{2}), 1));
    }

    @Test
    void streamsLargeInputWithBoundedReadsAndCorrectDigest() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            existingStages = new HashSet<>(paths.toList());
        }
        int length = 1_000_000;
        InputStream input = new InputStream() {
            int left = length;
            @Override public int read() { if (left-- > 0) return 42; return -1; }
            @Override public int read(byte[] b, int off, int len) {
                assertTrue(len <= 8192);
                if (left == 0) return -1;
                int count = Math.min(left, len);
                Arrays.fill(b, off, off + count, (byte) 42);
                left -= count;
                return count;
            }
        };
        UploadResult result = store.writeAndDigest("large", input, length);
        assertNotNull(observedStage);
        assertFalse(Files.exists(observedStage));
        assertEquals(length, result.byteCount());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(objects.get("large"))), result.sha256Hex());
    }

    @Test
    void archiveReadsAzureFormatAndRejectsConflictingDuplicate() throws Exception {
        OffsetDateTime at = OffsetDateTime.parse("2026-03-01T12:00:01Z");
        ArchivedDeletion entry = new ArchivedDeletion(41, 7, DeletionScope.DOCUMENT, 99, 3, at, at.plusSeconds(1), at, "{}");
        S3DeletionLedgerArchive archive = new S3DeletionLedgerArchive(store);
        archive.add(entry);
        assertArrayEquals(AzureDeletionLedgerArchive.toJson(entry), objects.get(AzureDeletionLedgerArchive.nameOf(entry)));
        assertEquals(List.of(entry), archive.readAll());
        archive.add(entry);
        objects.put(AzureDeletionLedgerArchive.nameOf(entry), "different".getBytes());
        assertThrows(IOException.class, () -> archive.add(entry));
    }

    @Test
    void archiveRequiresSeparateCredentialsAndBucket() {
        S3StorageConfig config = new S3StorageConfig();
        assertThrows(IllegalStateException.class, () -> config.archiveS3Client("https://s3.example", "auto", "same", "secret", "same", "files", "archive"));
        assertThrows(IllegalStateException.class, () -> config.archiveS3Client("https://s3.example", "auto", "other", "secret", "files-key", "same", "same"));
        assertThrows(IllegalArgumentException.class, () -> S3StorageConfig.client("http://s3.example", "auto", "key", "secret"));
    }
}
