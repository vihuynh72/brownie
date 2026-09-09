package io.github.vihuynh72.brownie.core.artifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the upload lifecycle against fakes, not a real database or
 * blob store -- what matters here is the sequencing and state-transition
 * logic {@link ArtifactService} owns, independent of any infrastructure.
 */
class ArtifactServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;

    @Test
    void fullUploadLifecycleSucceeds() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service = new ArtifactService(repository, blobStore, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);
        assertEquals(ArtifactStatus.UPLOADING, allocated.status());
        assertNull(allocated.byteCount());

        byte[] content = "hello world".getBytes();
        UploadResult result =
                service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(content));
        assertEquals(content.length, result.byteCount());
        assertFalse(result.sha256Hex().isBlank());

        Artifact finalized = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());
        assertEquals(ArtifactStatus.QUARANTINED, finalized.status());
        assertEquals((long) content.length, finalized.byteCount());
        assertEquals(result.sha256Hex(), finalized.sha256());
    }

    @Test
    void finalizingTwiceIsIdempotent() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service = new ArtifactService(repository, blobStore, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("x".getBytes()));

        Artifact first = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());
        Artifact second = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertEquals(first, second);
    }

    @Test
    void finalizingBeforeContentUploadedIsAConflict() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service = new ArtifactService(repository, new FakeBlobStore(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));
    }

    @Test
    void uploadingContentTwiceIsRejectedAsImmutable() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service = new ArtifactService(repository, new FakeBlobStore(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("first".getBytes()));

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.receiveContent(
                        WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("second".getBytes())));
    }

    @Test
    void contentExceedingTheLimitIsRejectedAndNothingIsRecorded() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service = new ArtifactService(repository, blobStore, 4, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);

        assertThrows(
                ArtifactTooLargeException.class,
                () -> service.receiveContent(
                        WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("way too long".getBytes())));

        Artifact stillUploading = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertNull(stillUploading.byteCount());
        assertTrue(blobStore.objects.isEmpty());
    }

    @Test
    void anAbandonedUploadCannotBeFinalized() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service = new ArtifactService(repository, new FakeBlobStore(), 1024, Duration.ofMillis(1));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);
        repository.backdateCreatedAt(allocated.id(), OffsetDateTime.now().minusHours(1));

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));
        Artifact rejected = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.REJECTED, rejected.status());
        assertEquals("EXPIRED_ABANDONED_UPLOAD", rejected.rejectionReason());
    }

    @Test
    void finalizingWithAMismatchedStoredSizeIsAConflict() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service = new ArtifactService(repository, blobStore, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("abcde".getBytes()));
        // Simulate the stored object having changed underneath us.
        blobStore.objects.put(allocated.blobKey(), new byte[] {1, 2});

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));
    }

    @Test
    void lazilyExpiringAnAbandonedUploadThatAlreadyHasContentRemovesItsOrphanedBlob() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service = new ArtifactService(repository, blobStore, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("x".getBytes()));
        assertTrue(blobStore.objects.containsKey(allocated.blobKey()));
        repository.backdateCreatedAt(allocated.id(), OffsetDateTime.now().minusHours(25));

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));

        Artifact rejected = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.REJECTED, rejected.status());
        assertFalse(blobStore.objects.containsKey(allocated.blobKey()));
    }

    /** A minimal in-memory stand-in with just enough behavior to exercise ArtifactService's own logic. */
    private static final class FakeArtifactRepository implements ArtifactRepository {

        private final Map<Long, Artifact> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override
        public Artifact initiateUpload(long workspaceId, long userId) {
            long id = ids.getAndIncrement();
            Artifact artifact = new Artifact(
                    id, workspaceId, "blob-" + id, ArtifactStatus.UPLOADING, null, null, null, OffsetDateTime.now(), null);
            byId.put(id, artifact);
            return artifact;
        }

        @Override
        public Optional<Artifact> find(long workspaceId, long userId, long artifactId) {
            Artifact artifact = byId.get(artifactId);
            return artifact != null && artifact.workspaceId() == workspaceId ? Optional.of(artifact) : Optional.empty();
        }

        @Override
        public Artifact recordUploadedContent(long workspaceId, long userId, long artifactId, long byteCount, String sha256) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.UPLOADING && current.byteCount() == null) {
                Artifact updated = new Artifact(
                        current.id(), current.workspaceId(), current.blobKey(), current.status(), byteCount, sha256,
                        current.rejectionReason(), current.createdAt(), current.finalizedAt());
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        @Override
        public Artifact finalizeUpload(long workspaceId, long userId, long artifactId) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.UPLOADING && current.byteCount() != null) {
                Artifact updated = new Artifact(
                        current.id(), current.workspaceId(), current.blobKey(), ArtifactStatus.QUARANTINED,
                        current.byteCount(), current.sha256(), current.rejectionReason(), current.createdAt(),
                        OffsetDateTime.now());
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        @Override
        public Artifact reject(long workspaceId, long userId, long artifactId, String reason) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.UPLOADING) {
                Artifact updated = new Artifact(
                        current.id(), current.workspaceId(), current.blobKey(), ArtifactStatus.REJECTED,
                        current.byteCount(), current.sha256(), reason, current.createdAt(), current.finalizedAt());
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        void backdateCreatedAt(long artifactId, OffsetDateTime createdAt) {
            Artifact current = byId.get(artifactId);
            byId.put(
                    artifactId,
                    new Artifact(
                            current.id(), current.workspaceId(), current.blobKey(), current.status(),
                            current.byteCount(), current.sha256(), current.rejectionReason(), createdAt,
                            current.finalizedAt()));
        }
    }

    private static final class FakeBlobStore implements BlobStore {

        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) throws IOException {
            byte[] buffer = content.readAllBytes();
            if (buffer.length > maxBytes) {
                throw new BlobSizeLimitExceededException(maxBytes);
            }
            objects.put(objectKey, buffer);
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                return new UploadResult(buffer.length, java.util.HexFormat.of().formatHex(digest.digest(buffer)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Optional<Long> sizeOf(String objectKey) {
            byte[] bytes = objects.get(objectKey);
            return bytes == null ? Optional.empty() : Optional.of((long) bytes.length);
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }
    }
}
