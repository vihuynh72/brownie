package io.github.vihuynh72.brownie.core.artifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DisplayFilenamesTest {

    @Test
    void nullInputStaysNull() {
        assertNull(DisplayFilenames.sanitize(null));
    }

    @Test
    void anOrdinaryNamePassesThroughUnchanged() {
        assertEquals("Meeting Notes.docx", DisplayFilenames.sanitize("Meeting Notes.docx"));
    }

    @Test
    void onlyTheLastPathSegmentIsKept() {
        assertEquals("passwd", DisplayFilenames.sanitize("../../etc/passwd"));
        assertEquals("evil.exe", DisplayFilenames.sanitize("C:\\Windows\\System32\\evil.exe"));
    }

    @Test
    void controlCharactersAreStripped() {
        char bell = (char) 7;
        String withControlCharacter = "notes" + bell + "txt";

        assertEquals("notestxt", DisplayFilenames.sanitize(withControlCharacter));
    }

    @Test
    void aNameThatSanitizesToNothingReturnsNull() {
        assertNull(DisplayFilenames.sanitize("///"));
        assertNull(DisplayFilenames.sanitize("   "));
    }

    @Test
    void aNameLongerThanTheLimitIsTruncated() {
        String longName = "a".repeat(400) + ".docx";

        String sanitized = DisplayFilenames.sanitize(longName);

        assertEquals(255, sanitized.length());
    }
}
