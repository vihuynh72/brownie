package io.github.vihuynh72.brownie.core.batch;

/**
 * The reader a resumed step would use is not the one the batch was created
 * with -- a deployment moved past it mid-fill, and continuing would number
 * or read rows differently than the rows already made.
 */
public class BatchReaderChangedException extends RuntimeException {

    public BatchReaderChangedException() {
        super("This batch's spreadsheet can no longer be read by the reader it was created with.");
    }
}
