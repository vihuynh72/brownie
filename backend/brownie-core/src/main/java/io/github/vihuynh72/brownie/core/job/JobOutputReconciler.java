package io.github.vihuynh72.brownie.core.job;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Reclaims bounded batches of temporary objects after a lost or terminal attempt. */
public final class JobOutputReconciler {

    private static final Logger log = LoggerFactory.getLogger(JobOutputReconciler.class);

    private final StagedOutputCleanupRepository cleanupRepository;
    private final BlobStore blobStore;

    public JobOutputReconciler(StagedOutputCleanupRepository cleanupRepository, BlobStore blobStore) {
        this.cleanupRepository = Objects.requireNonNull(cleanupRepository, "cleanupRepository must not be null");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
    }

    /**
     * Deletes objects first, then records cleanup. A process death between
     * those two operations is safe because BlobStore.delete is idempotent.
     */
    public int reconcileOnce(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive.");
        }
        List<StagedOutput> outputs = cleanupRepository.collectDiscardedOutputs(limit);
        int cleaned = 0;
        for (StagedOutput output : outputs) {
            if (output.state() != StagedOutputState.DISCARDED) {
                throw new IllegalStateException("Only discarded outputs may be reclaimed.");
            }
            try {
                blobStore.delete(output.metadata().objectKey());
                if (cleanupRepository.markDiscardedOutputCleaned(output.id(), output.metadata().objectKey())) {
                    cleaned++;
                }
            } catch (IOException failure) {
                log.warn("Could not reclaim temporary job output {}.", output.metadata().objectKey(), failure);
            }
        }
        return cleaned;
    }
}
