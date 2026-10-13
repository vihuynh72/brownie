package io.github.vihuynh72.brownie.core.batch;

/**
 * The export run is not one the caller may see: wrong id, wrong batch or
 * another workspace's. As with the batch itself, all three read the same
 * way.
 */
public final class BatchExportNotFoundException extends RuntimeException {

    public BatchExportNotFoundException(long batchId, long exportId) {
        super("No export " + exportId + " on batch " + batchId + ".");
    }
}
