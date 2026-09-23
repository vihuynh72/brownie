package io.github.vihuynh72.brownie.core.connector;

import java.util.Objects;

/**
 * A token as it is stored: encrypted, with the identifier of the key that
 * encrypted it and the one-time nonce used. Meaningless without that key,
 * which is never stored beside it.
 */
public record SealedToken(String keyId, byte[] nonce, byte[] ciphertext) {

    public SealedToken {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(ciphertext, "ciphertext");
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /** Only the key identifier: nothing that came out of the cipher goes into a log line, even encrypted. */
    @Override
    public String toString() {
        return "SealedToken[keyId=" + keyId + "]";
    }
}
