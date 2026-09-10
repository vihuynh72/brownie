package io.github.vihuynh72.brownie.core.text;

/**
 * Code-point-safe indexing over a Java {@code String}, which is natively
 * indexed by UTF-16 {@code char} units, not Unicode code points: any
 * character outside the Basic Multilingual Plane (most emoji, several
 * historic and less common scripts) occupies two {@code char}s as a
 * surrogate pair. Indexing or slicing by a raw {@code char} offset chosen
 * without knowing this can land in the middle of one, producing a string
 * with an unpaired surrogate -- not throwing, just silently corrupt.
 *
 * <p>This project's persisted text-offset contracts are defined in
 * Unicode code points, not {@code char} units, specifically so a
 * persisted offset means the same thing to any reader regardless of
 * internal representation. Every method here does the {@code char}-to-
 * code-point conversion through the JDK's own {@link String#codePointCount}
 * and {@link String#offsetByCodePoints}, rather than hand-rolled surrogate
 * detection.
 */
public final class CodePoints {

    private CodePoints() {
    }

    /** The number of Unicode code points in {@code text} -- less than {@code text.length()} whenever a surrogate pair is present. */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /**
     * The substring spanning code points {@code [startCodePoint,
     * endCodePointExclusive)}. Never splits a surrogate pair: both bounds
     * are converted to {@code char} indices through {@link
     * String#offsetByCodePoints}, which always lands on a real code-point
     * boundary.
     */
    public static String substring(String text, int startCodePoint, int endCodePointExclusive) {
        if (startCodePoint < 0 || endCodePointExclusive < startCodePoint) {
            throw new IllegalArgumentException(
                    "Invalid code point range [" + startCodePoint + ", " + endCodePointExclusive + ").");
        }
        int startChar = text.offsetByCodePoints(0, startCodePoint);
        int endChar = text.offsetByCodePoints(startChar, endCodePointExclusive - startCodePoint);
        return text.substring(startChar, endChar);
    }
}
