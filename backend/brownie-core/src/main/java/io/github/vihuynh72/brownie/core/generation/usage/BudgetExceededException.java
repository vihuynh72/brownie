package io.github.vihuynh72.brownie.core.generation.usage;

/** One more physical model call would exceed this run's own request, token, or cost bound -- the earliest-reached limit always wins, and this run stops rather than making the call. */
public class BudgetExceededException extends Exception {

    public BudgetExceededException(String message) {
        super(message);
    }
}
