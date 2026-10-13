package io.github.vihuynh72.brownie.core.batch;

/**
 * What one accept call did with one row: either a fresh revision whose
 * review is ACCEPTED for every item, or a skip whose code says exactly
 * why the row was left alone.
 */
public record AcceptRowResult(
        BatchRow row,
        boolean accepted,
        AcceptSkipCode code) {
}
