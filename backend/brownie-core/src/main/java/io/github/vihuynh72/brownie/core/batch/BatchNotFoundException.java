package io.github.vihuynh72.brownie.core.batch;

/** No batch by this id in this workspace -- answered like anything else that is not there, as a 404. */
public class BatchNotFoundException extends RuntimeException {

    private final long batchId;

    public BatchNotFoundException(long batchId) {
        super("Batch " + batchId + " is not available in this workspace.");
        this.batchId = batchId;
    }

    public long batchId() {
        return batchId;
    }
}
