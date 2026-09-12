package io.github.vihuynh72.brownie.core.text;

/**
 * Line-ending normalization ({@code \r\n} and bare {@code \r} both become
 * {@code \n}) that keeps a mapping back from every normalized code point
 * to where it came from in the original text, so a span expressed in
 * normalized-text coordinates can always be resolved back to the exact
 * original substring it was derived from -- never an approximation, never
 * a guess at which original characters a normalized position "probably"
 * corresponds to.
 *
 * <p>All offsets here are Unicode code points ({@link CodePoints}), not
 * {@code char} units, and not grapheme clusters: a base character plus a
 * combining mark are two separate code points and may be split by a span
 * boundary. Code-point safety only guarantees a span never lands inside a
 * single code point's own UTF-16 surrogate pair; it says nothing about
 * grapheme composition, which this project's persisted contracts do not
 * ask for (see the plan's own explicit choice of code points).
 */
public final class NormalizedText {

    private final String original;
    private final String normalized;

    /** {@code normalizedToOriginalCodePoint[i]} is the original code-point index where normalized code point {@code i} begins. Length is {@code normalizedLength + 1}, the last entry being the original text's own total code-point length -- a sentinel that lets a range ending at the very end of the normalized text resolve correctly. */
    private final int[] normalizedToOriginalCodePoint;

    private NormalizedText(String original, String normalized, int[] normalizedToOriginalCodePoint) {
        this.original = original;
        this.normalized = normalized;
        this.normalizedToOriginalCodePoint = normalizedToOriginalCodePoint;
    }

    public static NormalizedText normalizeLineEndings(String original) {
        StringBuilder normalized = new StringBuilder(original.length());
        // original.length() (a char count) is always >= the number of
        // normalized code points that will be produced: normalization only
        // ever removes or 1-for-1 replaces characters, never adds any, and
        // every normalized code point consumes at least one original char.
        // +1 for the trailing sentinel entry.
        int[] mapping = new int[original.length() + 1];
        int mappingSize = 0;

        int charIndex = 0;
        int originalCodePoint = 0;
        int length = original.length();
        while (charIndex < length) {
            int codePoint = original.codePointAt(charIndex);
            int charCount = Character.charCount(codePoint);

            if (codePoint == '\r') {
                boolean crlf = charIndex + charCount < length && original.codePointAt(charIndex + charCount) == '\n';
                mapping[mappingSize++] = originalCodePoint;
                normalized.append('\n');
                if (crlf) {
                    charIndex += charCount + Character.charCount('\n');
                    originalCodePoint += 2;
                } else {
                    charIndex += charCount;
                    originalCodePoint += 1;
                }
                continue;
            }

            mapping[mappingSize++] = originalCodePoint;
            normalized.appendCodePoint(codePoint);
            charIndex += charCount;
            originalCodePoint += 1;
        }
        mapping[mappingSize++] = originalCodePoint;

        int[] trimmed = new int[mappingSize];
        System.arraycopy(mapping, 0, trimmed, 0, mappingSize);
        return new NormalizedText(original, normalized.toString(), trimmed);
    }

    public String original() {
        return original;
    }

    public String normalized() {
        return normalized;
    }

    public int normalizedLength() {
        return normalizedToOriginalCodePoint.length - 1;
    }

    /**
     * The exact original substring that normalized code points {@code
     * [normalizedStartCodePoint, normalizedEndCodePointExclusive)} were
     * derived from. When the range covers a normalized {@code \n} that
     * replaced a {@code \r\n} pair, the returned original span includes
     * both original characters, not just one of them.
     */
    public OriginalSpan originalSpanFor(int normalizedStartCodePoint, int normalizedEndCodePointExclusive) {
        if (normalizedStartCodePoint < 0
                || normalizedEndCodePointExclusive < normalizedStartCodePoint
                || normalizedEndCodePointExclusive > normalizedLength()) {
            throw new IllegalArgumentException(
                    "Invalid normalized code point range [" + normalizedStartCodePoint + ", "
                            + normalizedEndCodePointExclusive + ") for normalized length " + normalizedLength() + ".");
        }
        int originalStart = normalizedToOriginalCodePoint[normalizedStartCodePoint];
        int originalEnd = normalizedToOriginalCodePoint[normalizedEndCodePointExclusive];
        return new OriginalSpan(originalStart, originalEnd, CodePoints.substring(original, originalStart, originalEnd));
    }

    /** {@code startCodePoint}/{@code endCodePoint} are offsets into the original text this {@link NormalizedText} was built from. */
    public record OriginalSpan(int startCodePoint, int endCodePoint, String text) {
    }
}
