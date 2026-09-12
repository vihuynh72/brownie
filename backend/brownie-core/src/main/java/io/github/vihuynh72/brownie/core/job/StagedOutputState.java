package io.github.vihuynh72.brownie.core.job;

/** Lifecycle for metadata describing a temporary worker-produced object. */
public enum StagedOutputState {
    STAGED,
    VERIFIED,
    ATTACHED,
    DISCARDED
}
