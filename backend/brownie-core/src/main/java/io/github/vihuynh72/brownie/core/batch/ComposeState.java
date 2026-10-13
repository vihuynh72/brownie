package io.github.vihuynh72.brownie.core.batch;

/**
 * Where a row stands in compose: PENDING waits for the compose step,
 * PROPOSED names a suggestion a person has yet to accept, FAILED and
 * NO_ALLOWANCE end it. NOT_NEEDED is every row of a batch with no COMPOSE
 * mapping.
 */
public enum ComposeState {
    NOT_NEEDED,
    PENDING,
    PROPOSED,
    FAILED,
    NO_ALLOWANCE
}
