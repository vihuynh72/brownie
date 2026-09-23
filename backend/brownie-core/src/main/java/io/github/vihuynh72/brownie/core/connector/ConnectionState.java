package io.github.vihuynh72.brownie.core.connector;

/** Where a connection stands. Only an {@link #ACTIVE} one holds a token. */
public enum ConnectionState {
    /** Usable: Brownie holds a token it can decrypt and the provider last accepted. */
    ACTIVE,
    /**
     * The token is gone, because the provider stopped accepting it or it can
     * no longer be decrypted; nothing is read until the person connects again.
     */
    RECONNECT_REQUIRED,
    /** Taken back by the person. Kept as a record of where earlier copies came from; never usable again. */
    DISCONNECTED
}
