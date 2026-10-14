package io.github.vihuynh72.brownie.core.mcp;

import java.util.Objects;

/**
 * The one moment a personal token exists in cleartext: minted, shown to
 * its owner once, and from then on known only by its SHA-256 and its
 * four-character hint.
 */
public record IssuedPersonalToken(long grantId, String token) {

    public IssuedPersonalToken {
        Objects.requireNonNull(token, "token");
    }
}
