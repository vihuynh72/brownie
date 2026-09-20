package io.github.vihuynh72.brownie.core.generation.usage;

import java.util.Objects;

/** One more physical model call would exceed a bound -- the earliest-reached limit always wins, and the caller stops rather than making the call. {@link #kind()} says which bound it was. */
public class BudgetExceededException extends Exception {

    private final UsageLimitKind kind;

    /** A bound of the run itself, which is the only kind that exists without a durable ledger. */
    public BudgetExceededException(String message) {
        this(UsageLimitKind.RUN, message);
    }

    public BudgetExceededException(UsageLimitKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public UsageLimitKind kind() {
        return kind;
    }
}
