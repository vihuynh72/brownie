package io.github.vihuynh72.brownie.core.retention;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * One pass of file housekeeping: bring stale uploads to the state they
 * belong in, then remove the stored bytes of files that were refused long
 * enough ago. Nothing a person can still use is ever touched; a ready file
 * is only refused here when nothing at all refers to it and it has been
 * that way for the whole unreferenced period.
 */
public final class ArtifactRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionSweeper.class);

    /** The retention periods, grouped because every pass needs all four together. */
    public record Periods(Duration abandonedAfter, Duration stuckScanAfter, Duration rejectedAfter, Duration unreferencedAfter) {

        public Periods {
            requireAtLeast(abandonedAfter, Duration.ofMinutes(1), "abandonedAfter");
            requireAtLeast(stuckScanAfter, Duration.ofMinutes(1), "stuckScanAfter");
            requireAtLeast(rejectedAfter, Duration.ZERO, "rejectedAfter");
            requireAtLeast(unreferencedAfter, Duration.ofHours(1), "unreferencedAfter");
        }

        private static void requireAtLeast(Duration value, Duration minimum, String name) {
            Objects.requireNonNull(value, name);
            if (value.compareTo(minimum) < 0) {
                throw new IllegalArgumentException(name + " must be at least " + minimum + ", was " + value + ".");
            }
        }
    }

    /** What one pass did, as counts only. */
    public record Result(int uploadsExpired, int payloadsRemoved, int payloadsFailed) {

        public boolean didAnything() {
            return uploadsExpired + payloadsRemoved + payloadsFailed > 0;
        }
    }

    private final ArtifactRetentionRepository retentionRepository;
    private final BlobStore blobStore;
    private final Periods periods;

    public ArtifactRetentionSweeper(ArtifactRetentionRepository retentionRepository, BlobStore blobStore, Periods periods) {
        this.retentionRepository = Objects.requireNonNull(retentionRepository, "retentionRepository must not be null");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
        this.periods = Objects.requireNonNull(periods, "periods must not be null");
    }

    /** Deletes first and records second, for the same reason every other blob cleanup here does: delete is a no-op for an object already gone, so a repeat is always safe. */
    public Result sweepOnce(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive.");
        }
        int expired = retentionRepository.expireStaleUploads(periods.abandonedAfter(), periods.stuckScanAfter(), limit);

        int removed = 0;
        int failed = 0;
        for (RemovablePayload payload :
                retentionRepository.collectRemovablePayloads(periods.rejectedAfter(), periods.unreferencedAfter(), limit)) {
            try {
                blobStore.delete(payload.blobKey());
                if (retentionRepository.markPayloadRemoved(payload.artifactId(), payload.blobKey())) {
                    removed++;
                }
            } catch (IOException | RuntimeException failure) {
                failed++;
                log.warn("Could not remove the stored bytes of refused artifact {}.", payload.artifactId(), failure);
            }
        }
        return new Result(expired, removed, failed);
    }
}
