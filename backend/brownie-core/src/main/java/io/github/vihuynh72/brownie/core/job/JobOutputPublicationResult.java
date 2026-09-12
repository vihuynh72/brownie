package io.github.vihuynh72.brownie.core.job;

/** Authoritative outcome of attempting to attach one verified worker output. */
public enum JobOutputPublicationResult {
    PUBLISHED,
    ALREADY_PUBLISHED,
    LOST_LEASE,
    CANCELLATION_REQUESTED,
    STALE_TARGET,
    EXPIRED_OUTPUT
}
