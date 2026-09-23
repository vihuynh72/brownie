package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorTokenCipher;
import io.github.vihuynh72.brownie.core.connector.SealedToken;
import io.github.vihuynh72.brownie.core.connector.TokenBinding;
import io.github.vihuynh72.brownie.core.connector.UnreadableTokenException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * AES-256 in GCM mode: each token encrypted under a fresh random 96-bit nonce,
 * with whose token it is (the {@link TokenBinding}) authenticated alongside
 * it. Decrypting with the wrong key, a changed ciphertext, or a ciphertext
 * moved to another person's row fails the authentication check, and all
 * three are reported the same way, as a token that cannot be read.
 *
 * <p>The key is a deployment secret, never in the database. A ciphertext
 * records which key made it; a key identifier other than the current one is
 * refused before any decryption is attempted, which is what makes changing
 * the key a clean event: every existing connection reads as needing its
 * person to connect again, rather than as corrupted.
 */
final class AesGcmConnectorTokenCipher implements ConnectorTokenCipher {

    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final String keyId;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    AesGcmConnectorTokenCipher(String keyId, byte[] keyBytes) {
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        if (keyBytes == null || keyBytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("The connector token key must be exactly " + KEY_BYTES + " bytes.");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    @Override
    public SealedToken seal(String token, TokenBinding binding) {
        Objects.requireNonNull(token, "token");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(binding.associatedData());
            return new SealedToken(keyId, nonce, cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is a JDK-guaranteed cipher; encrypting a token failed unexpectedly.", e);
        }
    }

    @Override
    public String open(SealedToken sealed, TokenBinding binding) {
        if (!keyId.equals(sealed.keyId())) {
            throw new UnreadableTokenException("The token was encrypted with key '" + sealed.keyId() + "', which is not the key in use.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            cipher.updateAAD(binding.associatedData());
            return new String(cipher.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new UnreadableTokenException("The stored token did not authenticate for this connection.");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A nonce of the wrong length, for instance: a stored value this cipher could never have written.
            throw new UnreadableTokenException("The stored token is not in a form this key can read.");
        }
    }
}
