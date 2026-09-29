package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;

import java.time.Instant;
import java.util.List;

/**
 * What one kind of action does with the provider: how it gets ready to send
 * (reading and checking everything it can before anything is claimed), and
 * how it asks afterwards what became of an earlier attempt. The service
 * around it owns the lifecycle; a handler only reads, sends once when told
 * to, and reports what it found.
 */
public interface ActionHandler {

    ActionType type();

    /** The kind of connection this action is carried out through. */
    ConnectorAccess access();

    /**
     * Reads and checks what sending needs, against the payload the person
     * approved (the bytes to send, the state of a target). Throws {@link
     * ActionChangedException} when something it depends on is no longer what
     * was approved; nothing is sent in either case.
     */
    PreparedWrite prepare(ActionRequest action, UsableConnection connection);

    /**
     * Asks the provider what became of this action's earlier attempts, at
     * least one of which was sent. {@link ActionOutcome.NotApplied} is
     * answered only when that is certain, which for some kinds of action
     * never can be.
     */
    ActionOutcome reconcile(ActionRequest action, UsableConnection connection, List<ActionAttempt> attempts, Instant now);

    /**
     * Whether what this kind makes is recognised at the provider only by what
     * it holds, with no id of its own: another action making the same change
     * would leave exactly the same thing there.
     */
    default boolean recognisedByContentOnly() {
        return false;
    }
}
