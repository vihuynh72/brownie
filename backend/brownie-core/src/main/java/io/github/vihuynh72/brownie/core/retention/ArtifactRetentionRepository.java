package io.github.vihuynh72.brownie.core.retention;

import java.time.Duration;
import java.util.List;

/**
 * The worker's view of file housekeeping: three routines, no table. Ages
 * are passed in rather than fixed in the database so the retention periods
 * stay ordinary configuration.
 */
public interface ArtifactRetentionRepository {

    /**
     * Refuses uploads abandoned for longer than {@code abandonedAfter},
     * returns a scan stuck for longer than {@code stuckScanAfter} to the
     * state it can be retried from, and refuses files left quarantined for
     * longer than {@code abandonedAfter}. Returns how many files changed.
     */
    int expireStaleUploads(Duration abandonedAfter, Duration stuckScanAfter, int limit);

    /**
     * Refuses ready files nothing has referred to for {@code unreferencedAfter},
     * then hands out refused files whose bytes are still stored and are
     * older than {@code rejectedAfter}.
     */
    List<RemovablePayload> collectRemovablePayloads(Duration rejectedAfter, Duration unreferencedAfter, int limit);

    boolean markPayloadRemoved(long artifactId, String blobKey);
}
