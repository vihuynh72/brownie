package io.github.vihuynh72.brownie.api.detail;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Seals a sensitive saved detail and opens it again, with AES-256-GCM and
 * a fresh random nonce per value stored as nonce || ciphertext || tag.
 * The 32-byte key comes from {@code brownie.details.key} /
 * {@code BROWNIE_DETAILS_KEY}, base64-encoded; a missing one leaves the API
 * up but {@link #seal} and {@link #open} refuse with {@link
 * DetailSealingUnavailableException}, and a malformed one fails startup
 * the way the MCP token-key check does.
 */
public class DetailSealer {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private DetailSealer(SecretKeySpec key) {
        this.key = key;
    }

    /** A sealer with no key: the API is up, but sealing refuses. */
    public static DetailSealer absent() {
        return new DetailSealer(null);
    }

    /** The configured sealer, or empty when no key was set. */
    public static Optional<DetailSealer> fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return Optional.empty();
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "BROWNIE_DETAILS_KEY is not base64. Generate one with: openssl rand -base64 32", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "BROWNIE_DETAILS_KEY must be 32 bytes base64-encoded; got " + keyBytes.length + " bytes."
                            + " Generate one with: openssl rand -base64 32");
        }
        return Optional.of(new DetailSealer(new SecretKeySpec(keyBytes, "AES")));
    }

    /** Whether a key is configured -- the {@code sealingAvailable} flag the API reports. */
    public boolean available() {
        return key != null;
    }

    /** The last four characters of the plain value, for masked display. */
    public static String hint(String plain) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("A sealed detail needs a non-empty value to hint at.");
        }
        return plain.substring(Math.max(0, plain.length() - 4));
    }

    public byte[] seal(String plain) {
        requireKey();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sealed = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, sealed, 0, nonce.length);
        System.arraycopy(ciphertext, 0, sealed, nonce.length, ciphertext.length);
        return sealed;
    }

    public String open(byte[] sealed) {
        requireKey();
        if (sealed == null || sealed.length < NONCE_BYTES + (TAG_BITS / 8)) {
            throw new IllegalArgumentException("A sealed detail is too short to be one.");
        }
        byte[] nonce = java.util.Arrays.copyOfRange(sealed, 0, NONCE_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(sealed, NONCE_BYTES, sealed.length);
        return new String(crypt(Cipher.DECRYPT_MODE, nonce, ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, byte[] nonce, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal the detail's value.", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new DetailSealingUnavailableException();
        }
    }
}
