package io.github.vihuynh72.brownie.core.batch;

/**
 * The file the batch was created from is no longer the file on the
 * artifact -- its bytes changed since the batch read them, so filling
 * further would fill from a different table than the rows were numbered by.
 */
public class BatchSourceChangedException extends RuntimeException {

    public BatchSourceChangedException() {
        super("The spreadsheet this batch was created from has changed.");
    }
}
