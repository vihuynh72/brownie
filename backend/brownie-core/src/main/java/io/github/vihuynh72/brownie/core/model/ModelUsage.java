package io.github.vihuynh72.brownie.core.model;

/**
 * The provider-billed token counts for one physical model call, as the
 * provider itself reported them -- not an estimate. A caller enforcing a
 * cost reservation settles against these numbers, never against whatever
 * it guessed the request would cost before sending it.
 */
public record ModelUsage(int inputTokens, int outputTokens) {

    public ModelUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must not be negative, was " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must not be negative, was " + outputTokens);
        }
    }
}
