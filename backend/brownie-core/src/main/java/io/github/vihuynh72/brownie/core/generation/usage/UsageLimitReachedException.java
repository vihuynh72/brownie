package io.github.vihuynh72.brownie.core.generation.usage;

import java.util.Objects;

/**
 * A monthly model allowance is used up, so a paid request was refused
 * before anything was sent. Different from a failure: nothing is wrong,
 * trying again now cannot help, and the month turning can.
 */
public class UsageLimitReachedException extends RuntimeException {

    private final UsageLimitKind kind;

    public UsageLimitReachedException(UsageLimitKind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public UsageLimitKind kind() {
        return kind;
    }
}
