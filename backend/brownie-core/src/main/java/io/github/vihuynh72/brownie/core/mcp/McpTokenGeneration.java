package io.github.vihuynh72.brownie.core.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints and fingerprints MCP bearer secrets. A minted token is
 * {@code brownie_pat_} (personal) or {@code brownie_dl_} (download link)
 * plus 32 random bytes in base64url, so the value alone says which kind
 * it is and carries enough entropy that storage never needs to see it:
 * every lookup is by SHA-256 and every display is the last four
 * characters.
 */
public final class McpTokenGeneration {

    public static final String PERSONAL_TOKEN_PREFIX = "brownie_pat_";
    public static final String DOWNLOAD_TOKEN_PREFIX = "brownie_dl_";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    private static final int HINT_LENGTH = 4;

    private McpTokenGeneration() {
    }

    public static String newPersonalToken() {
        return PERSONAL_TOKEN_PREFIX + randomUrlSafe();
    }

    public static String newDownloadToken() {
        return DOWNLOAD_TOKEN_PREFIX + randomUrlSafe();
    }

    public static boolean looksLikePersonalToken(String presented) {
        return presented != null && presented.startsWith(PERSONAL_TOKEN_PREFIX);
    }

    /**
     * The digest the database is asked about. Hashing happens on any
     * presented string, not only on well-formed tokens: an attacker-supplied
     * value just hashes to a digest that matches nothing, so the lookup
     * path stays identical for hits and misses.
     */
    public static String sha256Hex(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the platform.", exception);
        }
    }

    /** The four characters shown next to a grant so its owner can tell tokens apart; never more. */
    public static String hint(String raw) {
        if (raw.length() < HINT_LENGTH) {
            throw new IllegalArgumentException("A token is longer than its hint.");
        }
        return raw.substring(raw.length() - HINT_LENGTH);
    }

    private static String randomUrlSafe() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
