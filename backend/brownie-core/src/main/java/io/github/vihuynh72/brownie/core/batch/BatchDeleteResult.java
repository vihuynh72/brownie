package io.github.vihuynh72.brownie.core.batch;

/**
 * What the delete routine answered for a batch: it went away, it was never
 * there (or not for this member), or a step is still holding its lease and
 * the batch waits for the step to let go.
 */
public enum BatchDeleteResult {
    DELETED,
    NOT_FOUND,
    BUSY
}
