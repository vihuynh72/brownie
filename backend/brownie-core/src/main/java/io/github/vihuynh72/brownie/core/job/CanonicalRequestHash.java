package io.github.vihuynh72.brownie.core.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** A lowercase SHA-256 digest of canonical request bytes. */
public record CanonicalRequestHash(String value) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CanonicalRequestHash {
        Objects.requireNonNull(value, "value must not be null");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("Canonical request hash must be a lowercase SHA-256 hexadecimal digest.");
        }
    }

    public static CanonicalRequestHash sha256OfCanonicalText(String canonicalText) {
        Objects.requireNonNull(canonicalText, "canonicalText must not be null");
        try {
            return new CanonicalRequestHash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalText.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available in every supported Java runtime.", impossible);
        }
    }
}
