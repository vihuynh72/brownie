package io.github.vihuynh72.brownie.core.connector;

import java.util.List;

/**
 * Decrypted refresh tokens waiting to be revoked once the workspace holding
 * them has been deleted. Lives only in the memory of the request doing the
 * deletion; its string form says how many there are and nothing else.
 */
public record PendingRevocations(List<String> tokens) {

    public PendingRevocations {
        tokens = List.copyOf(tokens);
    }

    @Override
    public String toString() {
        return "PendingRevocations[" + tokens.size() + "]";
    }
}
