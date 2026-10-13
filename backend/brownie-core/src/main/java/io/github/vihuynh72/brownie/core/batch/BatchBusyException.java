package io.github.vihuynh72.brownie.core.batch;

/**
 * Another batch step already holds this workspace's step lease -- any batch,
 * any kind. The caller waits for the other step to finish or its lease to
 * lapse, then asks again; the batch itself is untouched.
 */
public class BatchBusyException extends RuntimeException {

    public BatchBusyException() {
        super("Another batch step is already running in this workspace.");
    }
}
