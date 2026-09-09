package io.github.vihuynh72.brownie.core.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The upload half of an artifact's lifecycle: allocate an owned upload,
 * receive its content, verify and finalize it. Depends only on the
 * interfaces above, so it has no framework or infrastructure dependency of
 * its own -- a JDBC repository and a real blob adapter are supplied by
 * whichever module wires this up.
 */
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactRepository artifactRepository;
    private final BlobStore blobStore;
    private final long maxUploadBytes;
    private final Duration abandonedUploadTtl;

    public ArtifactService(
            ArtifactRepository artifactRepository,
            BlobStore blobStore,
            long maxUploadBytes,
            Duration abandonedUploadTtl) {
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
        this.maxUploadBytes = maxUploadBytes;
        this.abandonedUploadTtl = abandonedUploadTtl;
    }

    /** {@code rawFilename} is whatever the client sent, sanitized here into pure display metadata before it is ever persisted. */
    public Artifact initiateUpload(long workspaceId, long userId, String rawFilename) {
        return artifactRepository.initiateUpload(workspaceId, userId, DisplayFilenames.sanitize(rawFilename));
    }

    /**
     * Streams {@code content} straight into blob storage, then classifies
     * and validates what was actually written by reading it back -- an
     * unsupported type or a package that fails its decompression bounds
     * is rejected here, before anything downstream ever parses or
     * previews it. Returns the artifact as actually recorded, not what
     * any caller declared.
     */
    public Artifact receiveContent(long workspaceId, long userId, long artifactId, InputStream content) {
        Artifact artifact = requireUploadable(workspaceId, userId, artifactId);
        if (artifact.byteCount() != null) {
            throw new ArtifactStateConflictException(
                    "Artifact " + artifactId + " already has recorded content; an upload's bytes are immutable"
                            + " once received.");
        }

        UploadResult result;
        try {
            result = blobStore.writeAndDigest(artifact.blobKey(), content, maxUploadBytes);
        } catch (BlobSizeLimitExceededException e) {
            throw new ArtifactTooLargeException(e.getMessage());
        } catch (IOException e) {
            throw new ArtifactStorageException("Failed to store uploaded content for artifact " + artifactId, e);
        }

        SupportedMediaType detectedMediaType = classifyOrReject(workspaceId, userId, artifact);

        Artifact recorded = artifactRepository.recordUploadedContent(
                workspaceId, userId, artifactId, result.byteCount(), result.sha256Hex(), detectedMediaType);
        boolean matches = Objects.equals(recorded.byteCount(), result.byteCount())
                && Objects.equals(recorded.sha256(), result.sha256Hex());
        if (!matches) {
            throw new ArtifactStateConflictException(
                    "Artifact " + artifactId + " already has different recorded content.");
        }
        return recorded;
    }

    /**
     * Reads back what was just written and classifies it. On any
     * validation failure, the artifact is rejected and its now-useless
     * blob removed before the exception propagates -- a rejected artifact
     * never sits around looking like it might still be usable.
     */
    private SupportedMediaType classifyOrReject(long workspaceId, long userId, Artifact artifact) {
        try (InputStream stored = blobStore.openStream(artifact.blobKey())) {
            return ArtifactContentInspector.inspect(stored);
        } catch (UnsupportedArtifactTypeException e) {
            rejectAndCleanUp(workspaceId, userId, artifact, "UNSUPPORTED_MEDIA_TYPE");
            throw e;
        } catch (ArtifactTooLargeException e) {
            rejectAndCleanUp(workspaceId, userId, artifact, "DECOMPRESSION_LIMIT_EXCEEDED");
            throw e;
        } catch (IOException e) {
            throw new ArtifactStorageException(
                    "Failed to read back uploaded content for artifact " + artifact.id() + " to classify it.", e);
        }
    }

    /** Verifies the uploaded object against what was recorded and transitions the artifact to QUARANTINED. Idempotent: calling this again once already QUARANTINED simply returns it unchanged. */
    public Artifact finalizeUpload(long workspaceId, long userId, long artifactId) {
        Artifact artifact = require(workspaceId, userId, artifactId);

        if (artifact.status() == ArtifactStatus.QUARANTINED) {
            return artifact;
        }
        if (artifact.status() != ArtifactStatus.UPLOADING) {
            throw new ArtifactStateConflictException(
                    "Artifact " + artifactId + " is " + artifact.status() + " and cannot be finalized.");
        }
        if (isAbandoned(artifact)) {
            expireAbandoned(workspaceId, userId, artifact);
        }
        if (artifact.byteCount() == null) {
            throw new ArtifactStateConflictException(
                    "Artifact " + artifactId + " has no uploaded content yet.");
        }

        long actualSize;
        try {
            actualSize = blobStore
                    .sizeOf(artifact.blobKey())
                    .orElseThrow(() -> new ArtifactStateConflictException(
                            "No uploaded content was found in storage for artifact " + artifactId + "."));
        } catch (IOException e) {
            throw new ArtifactStorageException("Failed to verify uploaded content for artifact " + artifactId, e);
        }
        if (actualSize != artifact.byteCount()) {
            throw new ArtifactStateConflictException(
                    "The stored object for artifact " + artifactId + " no longer matches its recorded upload size.");
        }

        Artifact result = artifactRepository.finalizeUpload(workspaceId, userId, artifactId);
        if (result.status() != ArtifactStatus.QUARANTINED) {
            throw new ArtifactStateConflictException(
                    "Artifact " + artifactId + " changed to " + result.status() + " and could not be finalized.");
        }
        return result;
    }

    private Artifact require(long workspaceId, long userId, long artifactId) {
        return artifactRepository
                .find(workspaceId, userId, artifactId)
                .orElseThrow(() -> new ArtifactNotFoundException(artifactId));
    }

    private Artifact requireUploadable(long workspaceId, long userId, long artifactId) {
        Artifact artifact = require(workspaceId, userId, artifactId);
        if (artifact.status() == ArtifactStatus.UPLOADING && isAbandoned(artifact)) {
            expireAbandoned(workspaceId, userId, artifact);
        }
        if (artifact.status() != ArtifactStatus.UPLOADING) {
            throw new ArtifactStateConflictException(
                    "Artifact " + artifactId + " is " + artifact.status() + " and cannot accept content.");
        }
        return artifact;
    }

    private boolean isAbandoned(Artifact artifact) {
        return artifact.createdAt().plus(abandonedUploadTtl).isBefore(OffsetDateTime.now());
    }

    /**
     * There is no proactive, system-wide sweep for an upload nobody ever
     * revisits -- see the comment in the artifact table's migration for
     * why one was tried and reverted. Instead, every path that would touch
     * an abandoned upload discovers it here, under the acting member's own
     * normal, already-authorized context, and cleans it up on the spot:
     * marks it REJECTED and removes any content it managed to write before
     * being abandoned.
     */
    private void expireAbandoned(long workspaceId, long userId, Artifact artifact) {
        rejectAndCleanUp(workspaceId, userId, artifact, "EXPIRED_ABANDONED_UPLOAD");
        throw new ArtifactStateConflictException(
                "Artifact " + artifact.id() + " was abandoned for too long and has expired.");
    }

    /**
     * Marks the artifact REJECTED and best-effort deletes its blob.
     * Callers reach this either before any content was ever written (the
     * delete is a harmless no-op, per {@link BlobStore#delete}) or right
     * after a just-written object failed classification -- deleting
     * unconditionally, rather than checking {@code artifact.byteCount()}
     * on the possibly-stale copy in hand, is what makes both cases safe.
     */
    private void rejectAndCleanUp(long workspaceId, long userId, Artifact artifact, String reason) {
        artifactRepository.reject(workspaceId, userId, artifact.id(), reason);
        try {
            blobStore.delete(artifact.blobKey());
        } catch (IOException e) {
            log.warn("Failed to delete blob {} for rejected artifact {}.", artifact.blobKey(), artifact.id(), e);
        }
    }
}
