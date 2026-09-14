package io.github.vihuynh72.brownie.core.example;

/**
 * Whether a completed example's own extracted structure actually looks like
 * an instance of the template it was attached to. Deliberately binary and
 * conservative: {@link #ALIGNED} means every one of the template's own
 * comparable field bindings was actually found in the example;
 * {@link #MISMATCHED_FAMILY} means at least one was not, which this plan's
 * own guidance treats as a real signal the example may describe a different
 * template rather than a partial, silently-tolerated match. See {@link
 * ExampleAligner} for exactly what "comparable" means.
 */
public enum ExampleAlignmentStatus {
    ALIGNED,
    MISMATCHED_FAMILY
}
