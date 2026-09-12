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
    void carriageReturnAndLineFeedAreStrippedLikeAnyOtherControlCharacter() {
        // A filename is exactly the kind of client-supplied string that
        // ends up in an HTTP response header later (Content-Disposition,
        // on the download/preview routes) -- CR/LF surviving here would be
        // a header-injection primitive, not just a display glitch.
        String attemptedHeaderInjection = "evil\r\nX-Injected: true";

        assertEquals("evilX-Injected: true", DisplayFilenames.sanitize(attemptedHeaderInjection));
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
