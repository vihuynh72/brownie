package io.github.vihuynh72.brownie.core.connector;

/**
 * Encrypts a provider's token for storage and decrypts it for use, with a key
 * the database never holds, so that the database alone, a dump of it or a
 * backup gives nobody a token a provider would accept.
 */
public interface ConnectorTokenCipher {

    SealedToken seal(String token, TokenBinding binding);

    /**
     * The token, or {@link UnreadableTokenException} when this ciphertext was
     * not made for this binding with the key in use now: a different key, a
     * changed ciphertext, or a ciphertext belonging to someone else.
     */
    String open(SealedToken sealed, TokenBinding binding);
}
