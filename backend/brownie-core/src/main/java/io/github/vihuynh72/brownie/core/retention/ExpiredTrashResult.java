package io.github.vihuynh72.brownie.core.retention;

import java.util.Objects;

/**
 * What happened to one trash entry whose time ran out. {@code outcome} is
 * the routine's own word: PURGED, NOT_OPEN, JOBS_STILL_STOPPING, or FAILED
 * when that one entry could not be carried out and was left for the next
 * sweep without undoing the others.
 */
public record ExpiredTrashResult(long requestId, String outcome) {

    public ExpiredTrashResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public boolean failed() {
        return "FAILED".equals(outcome);
    }
}
