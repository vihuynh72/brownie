package io.github.vihuynh72.brownie.core.source;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.MalwareScanner;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionStatus;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersion;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractor;
import io.github.vihuynh72.brownie.core.document.PlainTextStructuralGraph;
import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.InvalidEvidenceLocatorException;
import io.github.vihuynh72.brownie.core.evidence.ResolvedEvidence;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanNotFoundException;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code SourceService}'s orchestration (attach idempotency, create-
 * against-current-extraction, resolve-against-exact-historical-extraction,
 * hash verification, not-found handling) using only plain-text artifacts --
 * the per-format resolution logic itself is already covered directly, and
 * exhaustively, in {@code SourceServiceResolutionTest}.
 */
class SourceServiceTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long USER_ID = 1L;

    @Test
    void attachingTheSameArtifactTwiceReturnsTheSameSnapshotRatherThanDuplicating() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("Meeting notes.");

        SourceSnapshot first = fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);
        SourceSnapshot second = fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);

        assertEquals(first.id(), second.id());
        assertEquals(SourceKind.ARTIFACT, first.kind());
    }

    @Test
    void attachingRunsExtractionSoTheArtifactsContentIsAlreadyAvailable() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("Meeting notes.");

        fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);

        assertTrue(fixture.plainTextExtractionVersionRepository
                .findByArtifact(WORKSPACE_ID, USER_ID, artifactId, fixture.plainTextExtractor.parserVersion())
                .isPresent());
    }

    @Test
    void createSpanResolvesAgainstCurrentExtractionAndPersistsAMatchingHash() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("Meeting called to order.");
        SourceSnapshot snapshot = fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);

        SourceSpan span = fixture.sourceService.createSpan(WORKSPACE_ID, USER_ID, snapshot.id(), new EvidenceLocator.PlainText(0, 7));

        assertEquals(fixture.plainTextExtractor.parserVersion(), span.extractionParserVersion());
        assertTrue(span.excerptHash().matches("[0-9a-f]{64}"), "expected a 64-hex-character SHA-256 digest");
    }

    @Test
    void resolveSpanReturnsTheExactOriginalExcerpt() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("Meeting called to order.");
        SourceSnapshot snapshot = fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);
        SourceSpan span = fixture.sourceService.createSpan(WORKSPACE_ID, USER_ID, snapshot.id(), new EvidenceLocator.PlainText(0, 7));

        ResolvedEvidence resolved = fixture.sourceService.resolveSpan(WORKSPACE_ID, USER_ID, span.id());

        assertEquals("Meeting", resolved.excerptText());
        assertEquals(span.id(), resolved.span().id());
    }

    @Test
    void resolveSpanReReadsTheExactExtractionVersionTheSpanWasCreatedAgainstNotWhicheverIsLatest() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("Meeting called to order.");
        SourceSnapshot snapshot = fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);
        SourceSpan span = fixture.sourceService.createSpan(WORKSPACE_ID, USER_ID, snapshot.id(), new EvidenceLocator.PlainText(0, 7));

        // Simulate a parser upgrade: a second, differently-versioned
        // extraction now exists for the same artifact, with DIFFERENT
        // content at the same offsets. The old span must still resolve
        // against its own original version, not this new one.
        fixture.plainTextExtractionVersionRepository.saveComplete(
                WORKSPACE_ID, USER_ID, artifactId, "brownie-plain-text-graph-v2",
                new PlainTextStructuralGraph("brownie-plain-text-graph-v2", "Completely different text now.", "Completely different text now."));

        ResolvedEvidence resolved = fixture.sourceService.resolveSpan(WORKSPACE_ID, USER_ID, span.id());
        assertEquals("Meeting", resolved.excerptText(), "must still resolve against the original extraction version, not the newer one");
    }

    @Test
    void creatingASpanWithAnInvalidLocatorThrowsAndPersistsNothing() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("short");
        SourceSnapshot snapshot = fixture.sourceService.attachSnapshot(WORKSPACE_ID, USER_ID, artifactId);

        assertThrows(
                InvalidEvidenceLocatorException.class,
                () -> fixture.sourceService.createSpan(WORKSPACE_ID, USER_ID, snapshot.id(), new EvidenceLocator.PlainText(0, 999)));
        assertTrue(fixture.sourceSpanRepository.isEmpty());
    }

    @Test
    void resolvingANonexistentSpanThrows() {
        Fixture fixture = new Fixture();
        assertThrows(SourceSpanNotFoundException.class, () -> fixture.sourceService.resolveSpan(WORKSPACE_ID, USER_ID, 999_999L));
    }

    @Test
    void anotherWorkspacesArtifactCannotBeAttachedAsASource() {
        Fixture fixture = new Fixture();
        long artifactId = fixture.seedReadyPlainTextArtifact("Meeting notes.");

        assertThrows(RuntimeException.class, () -> fixture.sourceService.attachSnapshot(2L, USER_ID, artifactId));
    }

    /** Assembles a real {@code SourceService} over real {@code ArtifactService}/{@code DocumentExtractionService} and hand-written in-memory fakes. */
    private static final class Fixture {
        final InMemoryArtifactRepository artifactRepository = new InMemoryArtifactRepository();
        final InMemoryBlobStore blobStore = new InMemoryBlobStore();
        final InMemoryExtractionVersionRepository extractionVersionRepository = new InMemoryExtractionVersionRepository();
        final InMemoryPdfExtractionVersionRepository pdfExtractionVersionRepository = new InMemoryPdfExtractionVersionRepository();
        final InMemoryPlainTextExtractionVersionRepository plainTextExtractionVersionRepository =
                new InMemoryPlainTextExtractionVersionRepository();
        final InMemorySourceSnapshotRepository sourceSnapshotRepository = new InMemorySourceSnapshotRepository();
        final InMemorySourceSpanRepository sourceSpanRepository = new InMemorySourceSpanRepository();
        final PlainTextExtractor plainTextExtractor = new PlainTextExtractor();

        final ArtifactService artifactService = new ArtifactService(
                artifactRepository, blobStore, unusedMalwareScanner(), 10_000_000L, java.time.Duration.ofHours(24));
        final DocumentExtractionService documentExtractionService = new DocumentExtractionService(
                artifactService,
                unusedDocxExtractor(),
                extractionVersionRepository,
                unusedPdfExtractor(),
                pdfExtractionVersionRepository,
                plainTextExtractor,
                plainTextExtractionVersionRepository);
        final SourceService sourceService = new SourceService(
                documentExtractionService,
                extractionVersionRepository,
                pdfExtractionVersionRepository,
                plainTextExtractionVersionRepository,
                sourceSnapshotRepository,
                sourceSpanRepository);

        long seedReadyPlainTextArtifact(String text) {
            long id = artifactRepository.seedReady(WORKSPACE_ID, SupportedMediaType.PLAIN_TEXT);
            blobStore.put(artifactRepository.blobKeyOf(id), text.getBytes(StandardCharsets.UTF_8));
            return id;
        }

        private static DocxStructuralExtractor unusedDocxExtractor() {
            return new DocxStructuralExtractor() {
                @Override
                public String parserVersion() {
                    return "unused";
                }

                @Override
                public DocxExtractionOutcome extract(InputStream content) {
                    throw new UnsupportedOperationException("not used in this test");
                }
            };
        }

        private static PdfStructuralExtractor unusedPdfExtractor() {
            return new PdfStructuralExtractor() {
                @Override
                public String parserVersion() {
                    return "unused";
                }

                @Override
                public PdfExtractionOutcome extract(InputStream content) {
                    throw new UnsupportedOperationException("not used in this test");
                }
            };
        }

        private static MalwareScanner unusedMalwareScanner() {
            return content -> {
                throw new UnsupportedOperationException("not used in this test");
            };
        }
    }

    private static final class InMemoryArtifactRepository implements ArtifactRepository {
        private final Map<Long, Artifact> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        long seedReady(long workspaceId, SupportedMediaType mediaType) {
            long id = ids.getAndIncrement();
            byId.put(
                    id,
                    new Artifact(
                            id, workspaceId, "blob-" + id, ArtifactStatus.READY, 0L, "unused-sha", mediaType, "seed.txt", null,
                            OffsetDateTime.now(), OffsetDateTime.now()));
            return id;
        }

        String blobKeyOf(long artifactId) {
            return byId.get(artifactId).blobKey();
        }

        @Override
        public Optional<Artifact> find(long workspaceId, long userId, long artifactId) {
            Artifact artifact = byId.get(artifactId);
            return artifact != null && artifact.workspaceId() == workspaceId ? Optional.of(artifact) : Optional.empty();
        }

        @Override
        public Artifact initiateUpload(long workspaceId, long userId, String displayFilename) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Artifact recordUploadedContent(
                long workspaceId, long userId, long artifactId, long byteCount, String sha256, SupportedMediaType detectedMediaType) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Artifact finalizeUpload(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Artifact reject(long workspaceId, long userId, long artifactId, String reason) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Artifact beginScanning(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Artifact markReady(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Artifact revertToQuarantined(long workspaceId, long userId, long artifactId) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class InMemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> objects = new HashMap<>();

        void put(String key, byte[] bytes) {
            objects.put(key, bytes);
        }

        @Override
        public InputStream openStream(String objectKey) {
            return new ByteArrayInputStream(objects.get(objectKey));
        }

        @Override
        public UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Optional<Long> sizeOf(String objectKey) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public void delete(String objectKey) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class InMemoryExtractionVersionRepository implements ExtractionVersionRepository {
        private final Map<String, ExtractionVersion> byArtifactAndVersion = new HashMap<>();

        @Override
        public Optional<ExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return Optional.ofNullable(byArtifactAndVersion.get(artifactId + "/" + parserVersion));
        }

        @Override
        public ExtractionVersion saveComplete(
                long workspaceId, long userId, long artifactId, String parserVersion,
                io.github.vihuynh72.brownie.core.document.DocxStructuralGraph graph) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public ExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion,
                io.github.vihuynh72.brownie.core.document.DocxFeatureReport featureReport) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class InMemoryPdfExtractionVersionRepository implements PdfExtractionVersionRepository {
        @Override
        public Optional<PdfExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return Optional.empty();
        }

        @Override
        public PdfExtractionVersion saveComplete(
                long workspaceId, long userId, long artifactId, String parserVersion,
                io.github.vihuynh72.brownie.core.document.PdfStructuralGraph graph) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public PdfExtractionVersion saveUnsupported(
                long workspaceId, long userId, long artifactId, String parserVersion,
                io.github.vihuynh72.brownie.core.document.UnsupportedPdfReason reason, String detail) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public PdfExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class InMemoryPlainTextExtractionVersionRepository implements PlainTextExtractionVersionRepository {
        private final Map<String, PlainTextExtractionVersion> byArtifactAndVersion = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override
        public Optional<PlainTextExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion) {
            return Optional.ofNullable(byArtifactAndVersion.get(artifactId + "/" + parserVersion));
        }

        @Override
        public PlainTextExtractionVersion saveComplete(
                long workspaceId, long userId, long artifactId, String parserVersion, PlainTextStructuralGraph graph) {
            String key = artifactId + "/" + parserVersion;
            return byArtifactAndVersion.computeIfAbsent(
                    key,
                    k -> new PlainTextExtractionVersion(
                            ids.getAndIncrement(), workspaceId, artifactId, parserVersion, ExtractionStatus.COMPLETE, graph, null,
                            OffsetDateTime.now()));
        }

        @Override
        public PlainTextExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static final class InMemorySourceSnapshotRepository implements SourceSnapshotRepository {
        private final Map<Long, SourceSnapshot> byId = new HashMap<>();
        private final Map<Long, Long> snapshotIdByArtifactId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        @Override
        public Optional<SourceSnapshot> find(long workspaceId, long userId, long snapshotId) {
            SourceSnapshot snapshot = byId.get(snapshotId);
            return snapshot != null && snapshot.workspaceId() == workspaceId ? Optional.of(snapshot) : Optional.empty();
        }

        @Override
        public Optional<SourceSnapshot> findByArtifact(long workspaceId, long userId, long artifactId) {
            Long snapshotId = snapshotIdByArtifactId.get(artifactId);
            return snapshotId == null ? Optional.empty() : find(workspaceId, userId, snapshotId);
        }

        @Override
        public SourceSnapshot create(long workspaceId, long userId, long artifactId, SourceKind kind) {
            return byId.computeIfAbsent(nextIdFor(artifactId), id -> {
                SourceSnapshot snapshot = new SourceSnapshot(id, workspaceId, artifactId, kind, OffsetDateTime.now());
                snapshotIdByArtifactId.put(artifactId, id);
                return snapshot;
            });
        }

        private long nextIdFor(long artifactId) {
            Long existing = snapshotIdByArtifactId.get(artifactId);
            return existing != null ? existing : ids.getAndIncrement();
        }
    }

    private static final class InMemorySourceSpanRepository implements SourceSpanRepository {
        private final Map<Long, SourceSpan> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong(1);

        boolean isEmpty() {
            return byId.isEmpty();
        }

        @Override
        public Optional<SourceSpan> find(long workspaceId, long userId, long spanId) {
            SourceSpan span = byId.get(spanId);
            return span != null && span.workspaceId() == workspaceId ? Optional.of(span) : Optional.empty();
        }

        @Override
        public SourceSpan create(
                long workspaceId, long userId, long sourceSnapshotId, String extractionParserVersion, EvidenceLocator locator,
                String excerptHash) {
            long id = ids.getAndIncrement();
            SourceSpan span =
                    new SourceSpan(id, workspaceId, sourceSnapshotId, extractionParserVersion, locator, excerptHash, OffsetDateTime.now());
            byId.put(id, span);
            return span;
        }
    }
}
