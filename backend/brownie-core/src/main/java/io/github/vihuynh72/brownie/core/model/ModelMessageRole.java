package io.github.vihuynh72.brownie.core.model;

/**
 * A {@link ModelMessage}'s place in the conversation sent to the model.
 * Deliberately only these two: nothing in this codebase's first model
 * integration ever replays a prior assistant turn back to the model, so
 * there is no {@code ASSISTANT} role to send, only to receive (see {@link
 * ModelCompletion.Success}).
 */
public enum ModelMessageRole {
    SYSTEM,
    USER
}
