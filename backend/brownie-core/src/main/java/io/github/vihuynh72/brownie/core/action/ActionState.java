package io.github.vihuynh72.brownie.core.action;

/**
 * Where an action stands. Only {@link #EXECUTING} and {@link #RECONCILING}
 * mean a request is talking to the provider about it right now, and only
 * {@link #APPROVED} lets one start sending.
 *
 * <p>An action is written as awaiting approval from the start: it exists
 * only once there is something exact to approve, so there is no earlier
 * "proposed" moment to record.
 */
public enum ActionState {
    /** Shown to the person with its exact payload; nothing has been sent. Ends at its expiry. */
    AWAITING_APPROVAL,
    /** The person approved exactly this payload. Sending may start while the approval has not expired. */
    APPROVED,
    /** One attempt is sending it now. */
    EXECUTING,
    /** It happened, and reading it back found what was approved. */
    SUCCEEDED,
    /** It did not happen, or it happened and reading it back found something else. Never retried. */
    FAILED,
    /** It may or may not have happened. Nothing is sent again until the provider has been asked what became of it. */
    OUTCOME_UNKNOWN,
    /** One attempt is asking the provider what became of it now. */
    RECONCILING,
    /** The person withdrew it before anything was sent. */
    CANCELLED,
    /** Nothing was sent and the time to approve it, or to send it after approval, has passed. */
    EXPIRED;

    /** No change can follow. */
    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
    }
}
