package io.github.vihuynh72.brownie.core.job;

import java.util.List;

/** Narrow worker-only boundary for reclaiming objects that can no longer attach. */
public interface StagedOutputCleanupRepository {

    /** Returns at most {@code limit} discarded outputs that have not been cleaned. */
    List<StagedOutput> collectDiscardedOutputs(int limit);

    /** Records a successful idempotent object-store deletion for this exact output key. */
    boolean markDiscardedOutputCleaned(long stagedOutputId, String objectKey);
}
