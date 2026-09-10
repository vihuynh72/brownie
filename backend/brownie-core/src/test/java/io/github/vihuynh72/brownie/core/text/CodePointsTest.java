package io.github.vihuynh72.brownie.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodePointsTest {

    /** U+1F600 GRINNING FACE -- outside the Basic Multilingual Plane, represented as a UTF-16 surrogate pair (2 chars, 1 code point). */
    private static final String EMOJI = "😀";

    @Test
    void lengthCountsCodePointsNotChars() {
        assertEquals(4, "abcd".length());
        assertEquals(4, CodePoints.length("abcd"));

        String text = "a" + EMOJI + "b";
        assertEquals(4, text.length(), "sanity: char length includes both surrogate halves");
        assertEquals(3, CodePoints.length(text), "code point length must count the emoji as one");
    }

    @Test
    void substringOnPlainAsciiMatchesOrdinarySubstring() {
        assertEquals("bcd", CodePoints.substring("abcde", 1, 4));
        assertEquals("", CodePoints.substring("abcde", 2, 2));
        assertEquals("abcde", CodePoints.substring("abcde", 0, 5));
    }

    @Test
    void substringNeverSplitsASurrogatePair() {
        String text = "a" + EMOJI + "b"; // code points: 'a'(0), emoji(1), 'b'(2)
        assertEquals(EMOJI, CodePoints.substring(text, 1, 2));
        assertEquals("a" + EMOJI, CodePoints.substring(text, 0, 2));
        assertEquals(EMOJI + "b", CodePoints.substring(text, 1, 3));
        // A char-index-based String.substring(1, 2) on the same text would
        // instead return a single unpaired low surrogate -- the exact
        // corruption this class exists to prevent.
        String naive = text.substring(1, 2);
        assertEquals(1, naive.length());
        assertTrue(Character.isSurrogate(naive.charAt(0)));
    }

    @Test
    void invalidRangeThrows() {
        assertThrows(IllegalArgumentException.class, () -> CodePoints.substring("abc", -1, 2));
        assertThrows(IllegalArgumentException.class, () -> CodePoints.substring("abc", 2, 1));
    }
}
