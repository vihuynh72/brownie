package io.github.vihuynh72.brownie.core.artifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the upload lifecycle against fakes, not a real database, blob
 * store, or scanner -- what matters here is the sequencing and
 * state-transition logic {@link ArtifactService} owns, independent of any
 * infrastructure. The fake scanner defaults to reporting content clean,
 * so every existing test's happy path now reaches READY exactly as a real
 * scan of harmless test content would.
 */
class ArtifactServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 7L;

    @Test
    void fullUploadLifecycleSucceeds() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service = new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "notes.txt");
        assertEquals(ArtifactStatus.UPLOADING, allocated.status());
        assertEquals("notes.txt", allocated.displayFilename());
        assertNull(allocated.byteCount());

        byte[] content = "hello world".getBytes();
        Artifact afterUpload =
                service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(content));
        assertEquals(content.length, afterUpload.byteCount());
        assertFalse(afterUpload.sha256().isBlank());
        assertEquals(SupportedMediaType.PLAIN_TEXT, afterUpload.detectedMediaType());

        Artifact finalized = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());
        assertEquals(ArtifactStatus.READY, finalized.status());
        assertEquals((long) content.length, finalized.byteCount());
        assertEquals(afterUpload.sha256(), finalized.sha256());
    }

    @Test
    void aCleanScanReachesReady() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        ArtifactService service = new ArtifactService(repository, blobStore, scanner, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes()));

        Artifact result = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertEquals(ArtifactStatus.READY, result.status());
        assertEquals(1, scanner.scanCount);
    }

    @Test
    void anInfectedScanRejectsTheArtifactButRetainsItsBlob() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        scanner.willReturn(ScanResult.infected("Eicar-Test-Signature"));
        ArtifactService service = new ArtifactService(repository, blobStore, scanner, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("evil".getBytes()));

        Artifact result = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertEquals(ArtifactStatus.REJECTED, result.status());
        assertEquals("MALWARE_DETECTED", result.rejectionReason());
        // Unlike every other rejection path, a detected threat's blob is
        // deliberately retained, not destroyed.
        assertTrue(blobStore.objects.containsKey(allocated.blobKey()));
    }

    @Test
    void aScannerFailureRevertsToQuarantinedRatherThanRejecting() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        scanner.willFail(new IOException("connection refused"));
        ArtifactService service = new ArtifactService(repository, blobStore, scanner, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes()));

        assertThrows(
                MalwareScannerUnavailableException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));

        Artifact afterFailure = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.QUARANTINED, afterFailure.status());
        assertTrue(blobStore.objects.containsKey(allocated.blobKey()));
    }

    @Test
    void completingAgainAfterAScannerFailureRetriesTheScan() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        scanner.willFail(new IOException("connection refused"));
        ArtifactService service = new ArtifactService(repository, blobStore, scanner, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes()));
        assertThrows(
                MalwareScannerUnavailableException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));

        scanner.willReturn(ScanResult.ok());
        Artifact result = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertEquals(ArtifactStatus.READY, result.status());
        assertEquals(2, scanner.scanCount);
    }

    @Test
    void completingTwiceAfterReadyDoesNotScanAgain() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        ArtifactService service = new ArtifactService(repository, blobStore, scanner, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes()));
        service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        Artifact second = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertEquals(ArtifactStatus.READY, second.status());
        assertEquals(1, scanner.scanCount);
    }

    @Test
    void openContentReturnsTheExactBytesOfAReadyArtifact() throws IOException {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        byte[] content = "hello world".getBytes();
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(content));
        service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        try (ReadableArtifact readable = service.openContent(WORKSPACE_ID, USER_ID, allocated.id())) {
            assertEquals(ArtifactStatus.READY, readable.artifact().status());
            assertArrayEquals(content, readable.content().readAllBytes());
        }
    }

    @Test
    void openContentOnAnUnfinishedUploadIsAConflict() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service =
                new ArtifactService(repository, new FakeBlobStore(), new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.openContent(WORKSPACE_ID, USER_ID, allocated.id()));
    }

    @Test
    void openContentOnARejectedArtifactIsAConflictEvenThoughItsBlobStillExists() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        FakeMalwareScanner scanner = new FakeMalwareScanner();
        scanner.willReturn(ScanResult.infected("Eicar-Test-Signature"));
        ArtifactService service = new ArtifactService(repository, blobStore, scanner, 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("evil".getBytes()));
        service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.openContent(WORKSPACE_ID, USER_ID, allocated.id()));
    }

    @Test
    void openContentForAnArtifactInAnotherWorkspaceIsNotFound() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes()));
        service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        long otherWorkspaceId = WORKSPACE_ID + 1;
        assertThrows(
                ArtifactNotFoundException.class,
                () -> service.openContent(otherWorkspaceId, USER_ID, allocated.id()));
    }

    @Test
    void aSecondScanAttemptWhileOneIsAlreadyInFlightIsRejectedAsAConflict() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes()));
        // Drives the artifact to QUARANTINED, then claims the scan directly
        // at the repository level -- simulating a first caller that is
        // already mid-scan -- without going through the service, since
        // ArtifactService#finalizeUpload would scan synchronously and leave
        // nothing in flight to race against.
        repository.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());
        repository.beginScanning(WORKSPACE_ID, USER_ID, allocated.id());

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));
    }

    @Test
    void aFilenameIsSanitizedBeforeBeingPersisted() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service =
                new ArtifactService(repository, new FakeBlobStore(), new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "../../etc/passwd");

        assertEquals("passwd", allocated.displayFilename());
    }

    @Test
    void noFilenameSuppliedLeavesDisplayFilenameNull() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service =
                new ArtifactService(repository, new FakeBlobStore(), new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);

        assertNull(allocated.displayFilename());
    }

    @Test
    void aRealZippedDocxIsClassifiedAsDocx() throws IOException {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1_000_000, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "minutes.docx");
        byte[] docx = minimalOoxmlPackage();

        Artifact afterUpload =
                service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(docx));

        assertEquals(SupportedMediaType.DOCX, afterUpload.detectedMediaType());
    }

    @Test
    void aPdfSignatureIsClassifiedAsPdf() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "source.pdf");
        byte[] pdf = "%PDF-1.7\n...".getBytes();

        Artifact afterUpload =
                service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(pdf));

        assertEquals(SupportedMediaType.PDF, afterUpload.detectedMediaType());
    }

    @Test
    void aDeclaredFilenameExtensionDoesNotInfluenceByteBasedClassification() throws IOException {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1_000_000, Duration.ofHours(24));

        // Named like a PDF, but the real bytes are a DOCX -- classification
        // must follow what was actually read back, never the filename the
        // client happened to allocate with.
        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "report.pdf");
        byte[] docx = minimalOoxmlPackage();

        Artifact afterUpload =
                service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(docx));

        assertEquals("report.pdf", afterUpload.displayFilename());
        assertEquals(SupportedMediaType.DOCX, afterUpload.detectedMediaType());
    }

    @Test
    void aZipThatIsNotAnOoxmlPackageIsRejectedAndItsBlobRemoved() throws IOException {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1_000_000, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "not-a-docx.zip");
        byte[] plainZip = zipOf(Map.of("readme.txt", "just a zip, not a docx"));

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> service.receiveContent(
                        WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(plainZip)));

        Artifact rejected = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.REJECTED, rejected.status());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", rejected.rejectionReason());
        assertTrue(blobStore.objects.isEmpty());
    }

    @Test
    void randomBinaryContentIsRejectedAsUnsupported() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "mystery.bin");
        byte[] binary = {0x01, 0x02, 0x00, 0x03, (byte) 0xFF};

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(binary)));

        Artifact rejected = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.REJECTED, rejected.status());
    }

    @Test
    void aZipEntryEscapingItsPackageIsRejected() throws IOException {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1_000_000, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, "evil.docx");
        byte[] maliciousZip = zipOf(Map.of(
                "[Content_Types].xml", "<Types/>",
                "../../../evil.sh", "rm -rf /"));

        assertThrows(
                UnsupportedArtifactTypeException.class,
                () -> service.receiveContent(
                        WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream(maliciousZip)));
    }

    @Test
    void finalizingTwiceIsIdempotent() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("x".getBytes()));

        Artifact first = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());
        Artifact second = service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id());

        assertEquals(first, second);
    }

    @Test
    void finalizingBeforeContentUploadedIsAConflict() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service =
                new ArtifactService(repository, new FakeBlobStore(), new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));
    }

    @Test
    void uploadingContentTwiceIsRejectedAsImmutable() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service =
                new ArtifactService(repository, new FakeBlobStore(), new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
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
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 4, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);

        assertThrows(
                ArtifactTooLargeException.class,
                () -> service.receiveContent(
                        WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("way too long".getBytes())));

        Artifact stillUploading = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertNull(stillUploading.byteCount());
        assertTrue(blobStore.objects.isEmpty());
    }

    @Test
    void aClientDisconnectMidUploadLeavesNothingRecorded() {
        // A genuine read failure partway through the body -- exactly what
        // a truncated client connection looks like from this side -- is a
        // different failure shape from the size-limit case above (that one
        // is a length check this code enforces itself; this one is the
        // input stream itself throwing), and it is not exercised by any
        // other test.
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1_000_000, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        InputStream interrupted = new InputStream() {
            private int remaining = 5;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    throw new IOException("simulated client disconnect");
                }
                remaining--;
                return 'a';
            }
        };

        assertThrows(
                ArtifactStorageException.class,
                () -> service.receiveContent(WORKSPACE_ID, USER_ID, allocated.id(), interrupted));

        Artifact stillUploading = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertNull(stillUploading.byteCount());
        assertTrue(blobStore.objects.isEmpty());
    }

    @Test
    void anAbandonedUploadCannotBeFinalized() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        ArtifactService service =
                new ArtifactService(repository, new FakeBlobStore(), new FakeMalwareScanner(), 1024, Duration.ofMillis(1));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        repository.backdateCreatedAt(allocated.id(), OffsetDateTime.now().minusHours(1));

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.finalizeUpload(WORKSPACE_ID, USER_ID, allocated.id()));
        Artifact rejected = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.REJECTED, rejected.status());
        assertEquals("EXPIRED_ABANDONED_UPLOAD", rejected.rejectionReason());
    }

    @Test
    void receivingContentForAnAbandonedUploadExpiresItRatherThanAcceptingContent() {
        // finalizeUpload's own expiry check is covered above; receiveContent
        // reaches an independent isAbandoned/expireAbandoned call of its
        // own (via requireUploadable), never exercised by that test since
        // it never gets as far as uploading content at all.
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofMillis(1));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
        repository.backdateCreatedAt(allocated.id(), OffsetDateTime.now().minusHours(1));

        assertThrows(
                ArtifactStateConflictException.class,
                () -> service.receiveContent(
                        WORKSPACE_ID, USER_ID, allocated.id(), new ByteArrayInputStream("hello".getBytes())));
        Artifact rejected = repository.find(WORKSPACE_ID, USER_ID, allocated.id()).orElseThrow();
        assertEquals(ArtifactStatus.REJECTED, rejected.status());
        assertEquals("EXPIRED_ABANDONED_UPLOAD", rejected.rejectionReason());
        // The expiry check runs before anything is ever written to
        // storage -- "rather than accepting content" is a real claim
        // this makes, not just a possible-looking name for the test.
        assertTrue(blobStore.objects.isEmpty());
    }

    @Test
    void finalizingWithAMismatchedStoredSizeIsAConflict() {
        FakeArtifactRepository repository = new FakeArtifactRepository();
        FakeBlobStore blobStore = new FakeBlobStore();
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
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
        ArtifactService service =
                new ArtifactService(repository, blobStore, new FakeMalwareScanner(), 1024, Duration.ofHours(24));

        Artifact allocated = service.initiateUpload(WORKSPACE_ID, USER_ID, null);
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

    private static byte[] minimalOoxmlPackage() throws IOException {
        return zipOf(Map.of(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
                "word/document.xml",
                "<w:document/>"));
    }

    private static byte[] zipOf(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** A minimal in-memory stand-in with just enough behavior to exercise ArtifactService's own logic. */
    private static final class FakeArtifactRepository implements ArtifactRepository {

        private final Map<Long, Artifact> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override
        public Artifact initiateUpload(long workspaceId, long userId, String displayFilename) {
            long id = ids.getAndIncrement();
            Artifact artifact = new Artifact(
                    id, workspaceId, "blob-" + id, ArtifactStatus.UPLOADING, null, null, null, displayFilename,
                    null, OffsetDateTime.now(), null);
            byId.put(id, artifact);
            return artifact;
        }

        @Override
        public Optional<Artifact> find(long workspaceId, long userId, long artifactId) {
            Artifact artifact = byId.get(artifactId);
            return artifact != null && artifact.workspaceId() == workspaceId ? Optional.of(artifact) : Optional.empty();
        }

        @Override
        public Artifact recordUploadedContent(
                long workspaceId, long userId, long artifactId, long byteCount, String sha256, SupportedMediaType detectedMediaType) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.UPLOADING && current.byteCount() == null) {
                Artifact updated = new Artifact(
                        current.id(), current.workspaceId(), current.blobKey(), current.status(), byteCount, sha256,
                        detectedMediaType, current.displayFilename(), current.rejectionReason(), current.createdAt(),
                        current.finalizedAt());
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
                        current.byteCount(), current.sha256(), current.detectedMediaType(), current.displayFilename(),
                        current.rejectionReason(), current.createdAt(), OffsetDateTime.now());
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        @Override
        public Artifact reject(long workspaceId, long userId, long artifactId, String reason) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.UPLOADING || current.status() == ArtifactStatus.SCANNING) {
                Artifact updated = new Artifact(
                        current.id(), current.workspaceId(), current.blobKey(), ArtifactStatus.REJECTED,
                        current.byteCount(), current.sha256(), current.detectedMediaType(), current.displayFilename(),
                        reason, current.createdAt(), current.finalizedAt());
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        @Override
        public Artifact beginScanning(long workspaceId, long userId, long artifactId) {
            Artifact current = byId.get(artifactId);
            if (current.status() != ArtifactStatus.QUARANTINED) {
                throw new ArtifactStateConflictException(
                        "Artifact " + artifactId + " cannot begin scanning because it is " + current.status() + ".");
            }
            Artifact updated = withStatus(current, ArtifactStatus.SCANNING);
            byId.put(artifactId, updated);
            return updated;
        }

        @Override
        public Artifact markReady(long workspaceId, long userId, long artifactId) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.SCANNING) {
                Artifact updated = withStatus(current, ArtifactStatus.READY);
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        @Override
        public Artifact revertToQuarantined(long workspaceId, long userId, long artifactId) {
            Artifact current = byId.get(artifactId);
            if (current.status() == ArtifactStatus.SCANNING) {
                Artifact updated = withStatus(current, ArtifactStatus.QUARANTINED);
                byId.put(artifactId, updated);
                return updated;
            }
            return current;
        }

        private static Artifact withStatus(Artifact current, ArtifactStatus status) {
            return new Artifact(
                    current.id(), current.workspaceId(), current.blobKey(), status, current.byteCount(),
                    current.sha256(), current.detectedMediaType(), current.displayFilename(),
                    current.rejectionReason(), current.createdAt(), current.finalizedAt());
        }

        void backdateCreatedAt(long artifactId, OffsetDateTime createdAt) {
            Artifact current = byId.get(artifactId);
            byId.put(
                    artifactId,
                    new Artifact(
                            current.id(), current.workspaceId(), current.blobKey(), current.status(),
                            current.byteCount(), current.sha256(), current.detectedMediaType(),
                            current.displayFilename(), current.rejectionReason(), createdAt, current.finalizedAt()));
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
        public InputStream openStream(String objectKey) throws IOException {
            byte[] bytes = objects.get(objectKey);
            if (bytes == null) {
                throw new IOException("No such object: " + objectKey);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }
    }

    /** Defaults to reporting content clean; a test switches it to an infected verdict or a thrown failure as needed. */
    private static final class FakeMalwareScanner implements MalwareScanner {

        private ScanResult nextResult = ScanResult.ok();
        private IOException nextFailure;
        int scanCount = 0;

        @Override
        public ScanResult scan(InputStream content) throws IOException {
            scanCount++;
            content.readAllBytes();
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextResult;
        }

        void willReturn(ScanResult result) {
            this.nextResult = result;
            this.nextFailure = null;
        }

        void willFail(IOException failure) {
            this.nextFailure = failure;
        }
    }
}
