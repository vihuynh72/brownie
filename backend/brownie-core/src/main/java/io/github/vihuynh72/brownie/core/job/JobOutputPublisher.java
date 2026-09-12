package io.github.vihuynh72.brownie.core.job;

import io.github.vihuynh72.brownie.core.artifact.ArtifactContentInspector;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStateConflictException;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.artifact.BlobAlreadyExistsException;
import io.github.vihuynh72.brownie.core.artifact.BlobSizeLimitExceededException;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Writes a deterministic, attempt-scoped temporary object, verifies its
 * persisted bytes, then asks the lease repository to attach it atomically.
 * No database transaction remains open while object storage is accessed.
 */
public final class JobOutputPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobOutputPublisher.class);
    private static final int BUFFER_SIZE = 8192;

    private final JobLeaseRepository jobLeaseRepository;
    private final BlobStore blobStore;
    private final long maxOutputBytes;
    private final Duration stagedOutputTtl;

    public JobOutputPublisher(
            JobLeaseRepository jobLeaseRepository,
            BlobStore blobStore,
            long maxOutputBytes,
            Duration stagedOutputTtl) {
        this.jobLeaseRepository = Objects.requireNonNull(jobLeaseRepository, "jobLeaseRepository must not be null");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive.");
        }
        if (stagedOutputTtl == null || stagedOutputTtl.isZero() || stagedOutputTtl.isNegative()) {
            throw new IllegalArgumentException("stagedOutputTtl must be positive.");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.stagedOutputTtl = stagedOutputTtl;
    }

    /**
     * Reuses a matching temporary object after a process restart instead of
     * overwriting it. A new fencing token yields a different key, so a
     * recovered attempt cannot replace an older attempt's bytes.
     */
    public StagedJobOutput stage(LeasedJob leasedJob, String outputKind, InputStream content) {
        Objects.requireNonNull(leasedJob, "leasedJob must not be null");
        Objects.requireNonNull(content, "content must not be null");

        String objectKey = temporaryObjectKey(leasedJob, outputKind);
        boolean created = false;
        try {
            try {
                blobStore.writeNewAndDigest(objectKey, content, maxOutputBytes);
                created = true;
            } catch (BlobAlreadyExistsException ignored) {
                // A duplicate delivery may be resuming the exact same lease.
            }

            VerifiedBlob verified = verify(objectKey);
            SupportedMediaType mediaType = inspect(objectKey);
            StagedOutputRequest request = new StagedOutputRequest(
                    outputKind,
                    objectKey,
                    verified.sha256(),
                    verified.byteCount(),
                    OffsetDateTime.now(ZoneOffset.UTC).plus(stagedOutputTtl));
            StagedOutput staged = jobLeaseRepository
                    .recordStagedOutput(leasedJob.leaseToken(), request)
                    .orElseThrow(() -> new ArtifactStateConflictException(
                            "The lease ended before its temporary output could be recorded."));
            return new StagedJobOutput(staged, mediaType);
        } catch (IOException e) {
            if (created) {
                deleteBestEffort(objectKey);
            }
            throw new ArtifactStorageException("Could not write or verify temporary job output.", e);
        } catch (RuntimeException e) {
            if (created) {
                deleteBestEffort(objectKey);
            }
            throw e;
        }
    }

    /** Re-reads the stored bytes immediately before the database attachment. */
    public JobOutputPublication publish(LeasedJob leasedJob, StagedJobOutput stagedOutput) {
        Objects.requireNonNull(leasedJob, "leasedJob must not be null");
        Objects.requireNonNull(stagedOutput, "stagedOutput must not be null");
        requireSameAttempt(leasedJob, stagedOutput.stagedOutput());

        StagedOutputRequest metadata = stagedOutput.stagedOutput().metadata();
        try {
            VerifiedBlob verified = verify(metadata.objectKey());
            if (!metadata.sha256().equals(verified.sha256()) || !metadata.byteCount().equals(verified.byteCount())) {
                throw new ArtifactStateConflictException("Temporary output bytes no longer match their staged integrity metadata.");
            }
            SupportedMediaType observedMediaType = inspect(metadata.objectKey());
            if (observedMediaType != stagedOutput.detectedMediaType()) {
                throw new ArtifactStateConflictException("Temporary output media type changed after staging.");
            }

            JobOutputPublication publication = jobLeaseRepository.publishStagedOutput(
                    leasedJob.leaseToken(), metadata.outputKind(), observedMediaType);
            if (!publication.hasPublishedArtifact()) {
                deleteBestEffort(metadata.objectKey());
            }
            return publication;
        } catch (IOException e) {
            throw new ArtifactStorageException("Could not re-verify temporary job output.", e);
        }
    }

    /** Convenience path for a worker that has no separate handoff boundary. */
    public JobOutputPublication stageAndPublish(LeasedJob leasedJob, String outputKind, InputStream content) {
        return publish(leasedJob, stage(leasedJob, outputKind, content));
    }

    private VerifiedBlob verify(String objectKey) throws IOException {
        long recordedSize = blobStore.sizeOf(objectKey)
                .orElseThrow(() -> new ArtifactStateConflictException("Temporary output is missing from object storage."));
        if (recordedSize > maxOutputBytes) {
            throw new BlobSizeLimitExceededException(maxOutputBytes);
        }
        MessageDigest digest = sha256Digest();
        long total = 0;
        try (InputStream stored = blobStore.openStream(objectKey)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = stored.read(buffer)) != -1) {
                total += read;
                if (total > maxOutputBytes) {
                    throw new BlobSizeLimitExceededException(maxOutputBytes);
                }
                digest.update(buffer, 0, read);
            }
        }
        if (total != recordedSize) {
            throw new ArtifactStateConflictException("Temporary output changed while it was being verified.");
        }
        return new VerifiedBlob(total, HexFormat.of().formatHex(digest.digest()));
    }

    private SupportedMediaType inspect(String objectKey) throws IOException {
        try (InputStream stored = blobStore.openStream(objectKey)) {
            return ArtifactContentInspector.inspect(stored);
        }
    }

    private void deleteBestEffort(String objectKey) {
        try {
            blobStore.delete(objectKey);
        } catch (IOException failure) {
            log.warn("Could not delete discarded temporary job output {}.", objectKey, failure);
        }
    }

    private static String temporaryObjectKey(LeasedJob leasedJob, String outputKind) {
        // StagedOutputRequest performs the output-kind format validation.
        new StagedOutputRequest(outputKind, "placeholder", null, null, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        return "temporary/workspace-" + leasedJob.job().workspaceId()
                + "/job-" + leasedJob.job().id()
                + "/attempt-" + leasedJob.leaseToken().fencingToken()
                + "/" + outputKind;
    }

    private static void requireSameAttempt(LeasedJob leasedJob, StagedOutput stagedOutput) {
        if (stagedOutput.jobId() != leasedJob.job().id()
                || stagedOutput.fencingToken() != leasedJob.leaseToken().fencingToken()
                || !stagedOutput.workerId().equals(leasedJob.leaseToken().workerId())) {
            throw new IllegalArgumentException("The staged output does not belong to this lease attempt.");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM.", e);
        }
    }

    private record VerifiedBlob(long byteCount, String sha256) {
    }
}
