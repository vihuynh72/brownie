package io.github.vihuynh72.brownie.core.generation;

/**
 * Cooperative cancellation, checked before each paid model call so a
 * cancelled run stops spending. Nothing in this codebase yet wires a real
 * cancellation request through to one of these; {@link #never()} is the
 * honest default until one does.
 */
@FunctionalInterface
public interface CancellationSignal {

    boolean isCancellationRequested();

    static CancellationSignal never() {
        return () -> false;
    }
}
