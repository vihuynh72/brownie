package io.github.vihuynh72.brownie.core.generation.usage;

/**
 * Which limit refused a model request. The difference matters to whoever
 * is told about it: a run that reached its own bound can be started again,
 * a workspace that used its month's allowance cannot until the month turns,
 * and the shared allowance running out is nobody's fault in particular.
 */
public enum UsageLimitKind {
    /** This run's own bound on requests, tokens or reserved spend. */
    RUN,
    /** This workspace's allowance for the current calendar month. */
    WORKSPACE_MONTH,
    /** Everyone's shared allowance for the current calendar month. */
    GLOBAL_MONTH,
    /** The ledger would not take the reservation because the caller no longer holds the job it is spending for. */
    LEASE_LOST
}
