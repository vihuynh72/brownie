package io.github.vihuynh72.brownie.core.generation;

/**
 * Cooperative cancellation, checked before each paid model call -- this
 * plan's own "check it before each paid call" rule. Nothing in this
 * codebase yet wires a real cancellation request through to one of these
 * (that needs the durable job/run infrastructure a later phase builds);
 * {@link #never()} is the honest default until one does.
 */
@FunctionalInterface
public interface CancellationSignal {

    boolean isCancellationRequested();

    static CancellationSignal never() {
        return () -> false;
    }
}
