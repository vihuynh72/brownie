package io.github.vihuynh72.brownie.core.model;

import java.util.Objects;

/**
 * One turn in the ordered conversation sent to the model. The gateway
 * itself does not know what a "template rule" or "source excerpt" is --
 * whoever builds a {@link ModelRequest} decides how many {@code SYSTEM}
 * and {@code USER} messages to send and in what order, and that ordering
 * is exactly how this codebase's prompt-trust separation gets enforced in
 * practice: application policy belongs in {@code SYSTEM} messages sent
 * before any {@code USER} message that carries content originating from a
 * document or a person other than the operator, so that content can never
 * occupy the position of an instruction.
 */
public record ModelMessage(ModelMessageRole role, String content) {

    public ModelMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
