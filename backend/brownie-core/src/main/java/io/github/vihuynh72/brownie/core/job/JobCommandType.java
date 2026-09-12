package io.github.vihuynh72.brownie.core.job;

/** The command namespace used when scoping an idempotency key. */
public enum JobCommandType {
    ENQUEUE("job.enqueue"),
    REQUEST_CANCELLATION("job.request-cancellation"),
    REQUEST_RESUME("job.request-resume");

    private final String operation;

    JobCommandType(String operation) {
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
