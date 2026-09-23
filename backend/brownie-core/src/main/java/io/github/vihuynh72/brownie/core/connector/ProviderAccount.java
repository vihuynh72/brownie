package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/**
 * The outside account a consent was given from: the provider's own stable
 * identifier for it, which is what decides whether two consents came from
 * the same account, and its address, which is only ever shown back to the
 * person and may be absent.
 */
public record ProviderAccount(String id, String email) {

    public ProviderAccount {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A provider account needs an identifier.");
        }
    }
}
