package io.github.vihuynh72.brownie.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NormalizedTextTest {

    private static final String EMOJI = "😀";

    @Test
    void crlfBecomesLf() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\r\nb");
        assertEquals("a\nb", result.normalized());
        assertEquals("a\r\nb", result.original());
    }

    @Test
    void bareCrBecomesLf() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\rb");
        assertEquals("a\nb", result.normalized());
    }

    @Test
    void bareLfIsUnchanged() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\nb");
        assertEquals("a\nb", result.normalized());
    }

    @Test
    void mixedLineEndingsAllNormalize() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\r\nb\rc\nd");
        assertEquals("a\nb\nc\nd", result.normalized());
    }

    @Test
    void consecutiveCrlfPairsEachNormalizeSeparately() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\r\n\r\nb");
        assertEquals("a\n\nb", result.normalized());
    }

    @Test
    void emptyStringNormalizesToEmptyString() {
        NormalizedText result = NormalizedText.normalizeLineEndings("");
        assertEquals("", result.normalized());
        assertEquals(0, result.normalizedLength());
        assertEquals("", result.originalSpanFor(0, 0).text());
    }

    @Test
    void textWithNoLineEndingsIsIdentityMappedPositionForPosition() {
        String text = "hello world";
        NormalizedText result = NormalizedText.normalizeLineEndings(text);
        assertEquals(text, result.normalized());
        for (int i = 0; i < text.length(); i++) {
            var span = result.originalSpanFor(i, i + 1);
            assertEquals(i, span.startCodePoint(), "position " + i);
            assertEquals(i + 1, span.endCodePoint(), "position " + i);
            assertEquals(String.valueOf(text.charAt(i)), span.text(), "position " + i);
        }
    }

    @Test
    void fullRangeResolvesToTheEntireOriginalText() {
        String original = "line one\r\nline two\rline three";
        NormalizedText result = NormalizedText.normalizeLineEndings(original);
        var span = result.originalSpanFor(0, result.normalizedLength());
        assertEquals(0, span.startCodePoint());
        assertEquals(CodePoints.length(original), span.endCodePoint());
        assertEquals(original, span.text());
    }

    @Test
    void aSpanCoveringOnlyTheNormalizedNewlineRecoversBothOriginalCrAndLf() {
        // Code points: 'a'(0) '\n'(1, was "\r\n") 'b'(2)
        NormalizedText result = NormalizedText.normalizeLineEndings("a\r\nb");
        var span = result.originalSpanFor(1, 2);
        assertEquals("\r\n", span.text(), "the single normalized newline must resolve back to both original characters");
        assertEquals(1, span.startCodePoint());
        assertEquals(3, span.endCodePoint());
    }

    @Test
    void aSpanExcludingTheNormalizedNewlineDoesNotPullInTheOriginalCrlf() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\r\nb");
        var beforeNewline = result.originalSpanFor(0, 1);
        assertEquals("a", beforeNewline.text());
        var afterNewline = result.originalSpanFor(2, 3);
        assertEquals("b", afterNewline.text());
    }

    @Test
    void aSurrogatePairSurvivesNormalizationAndSpanResolutionIntact() {
        String original = EMOJI + "\r\n" + EMOJI;
        NormalizedText result = NormalizedText.normalizeLineEndings(original);
        assertEquals(EMOJI + "\n" + EMOJI, result.normalized());
        assertEquals(3, result.normalizedLength(), "emoji, newline, emoji -- three code points, not six chars");

        var firstEmoji = result.originalSpanFor(0, 1);
        assertEquals(EMOJI, firstEmoji.text());
        var secondEmoji = result.originalSpanFor(2, 3);
        assertEquals(EMOJI, secondEmoji.text());
    }

    @Test
    void invalidRangeThrows() {
        NormalizedText result = NormalizedText.normalizeLineEndings("abc");
        assertThrows(IllegalArgumentException.class, () -> result.originalSpanFor(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> result.originalSpanFor(2, 1));
        assertThrows(IllegalArgumentException.class, () -> result.originalSpanFor(0, result.normalizedLength() + 1));
    }

    @Test
    void aLoneTrailingCrWithNothingAfterItIsStillNormalized() {
        NormalizedText result = NormalizedText.normalizeLineEndings("a\r");
        assertEquals("a\n", result.normalized());
        var span = result.originalSpanFor(1, 2);
        assertEquals("\r", span.text());
    }
}
