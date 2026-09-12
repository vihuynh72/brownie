package io.github.vihuynh72.brownie.core.job;

/**
 * Classifies a failed work attempt before a retry is scheduled. Only the
 * explicitly transient kinds can be retried automatically.
 */
public enum JobFailureKind {
    TRANSIENT_PROVIDER(true),
    TRANSIENT_SERVER(true),
    TRANSIENT_NETWORK(true),
    DETERMINISTIC(false),
    WAITING_FOR_INPUT(false);

    private final boolean retriable;

    JobFailureKind(boolean retriable) {
        this.retriable = retriable;
    }

    public boolean retriable() {
        return retriable;
    }
}
