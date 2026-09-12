package io.github.vihuynh72.brownie.core.job;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Metadata about a temporary object written outside the database. The bytes
 * stay in the object store; this request contains only its opaque location
 * and integrity facts needed for later verification.
 */
public record StagedOutputRequest(
        String outputKind,
        String objectKey,
        String sha256,
        Long byteCount,
        OffsetDateTime expiresAt) {

    private static final Pattern KIND_FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public StagedOutputRequest {
        Objects.requireNonNull(outputKind, "outputKind must not be null");
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (outputKind.length() > 100 || !KIND_FORMAT.matcher(outputKind).matches()) {
            throw new IllegalArgumentException("Output kind must be a lowercase machine identifier up to 100 characters.");
        }
        if (objectKey.isBlank() || objectKey.length() > 512) {
            throw new IllegalArgumentException("Object key must contain non-blank text up to 512 characters.");
        }
        if ((sha256 == null) != (byteCount == null)) {
            throw new IllegalArgumentException("sha256 and byteCount must either both be present or both be absent.");
        }
        if (sha256 != null && !SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 hexadecimal digest.");
        }
        if (byteCount != null && byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative.");
        }
    }
}
