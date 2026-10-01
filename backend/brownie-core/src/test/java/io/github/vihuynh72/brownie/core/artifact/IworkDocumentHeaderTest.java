package io.github.vihuynh72.brownie.core.artifact;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The start of an iWork document part, decoded only as far as the first object's type and never past a fixed buffer. */
class IworkDocumentHeaderTest {

    @Test
    void readsTheFirstObjectsTypeFromASingleLiteral() {
        byte[] pages = ArtifactContentInspectorFormatTest.iwa(10000);
        byte[] numbers = ArtifactContentInspectorFormatTest.iwa(1);

        assertEquals(IworkDocumentHeader.PAGES_DOCUMENT, IworkDocumentHeader.firstMessageType(pages, pages.length));
        assertEquals(1, IworkDocumentHeader.firstMessageType(numbers, numbers.length));
    }

    /**
     * The same header compressed the way Snappy would when bytes repeat:
     * a literal, a copy of four bytes from two back (overlapping itself),
     * then a literal again. The message info lists its version three times,
     * which is where the repetition is.
     */
    @Test
    void followsCopiesWithinTheBlock() {
        byte[] literalThenCopy = {
            0x00, 0x11, 0x00, 0x00, // chunk header: a block of 17 bytes
            0x10, // decoded length 16
            0x24, 0x0F, 0x08, 0x01, 0x12, 0x0B, 0x08, (byte) 0x90, 0x4E, 0x10, 0x01, // literal of 10
            0x01, 0x02, // copy 4 from 2 back: 10 01 10 01
            0x04, 0x18, 0x00 // literal of 2
        };

        assertEquals(10000, IworkDocumentHeader.firstMessageType(literalThenCopy, literalThenCopy.length));
    }

    @Test
    void anythingThatDoesNotReadAsExpectedIsUnrecognized() {
        byte[] pages = ArtifactContentInspectorFormatTest.iwa(10000);
        byte[] copyBeforeAnything = {0x00, 0x04, 0x00, 0x00, 0x10, 0x01, 0x02, 0x00};
        byte[] endlessVarint = new byte[64];
        Arrays.fill(endlessVarint, (byte) 0xFF);
        endlessVarint[0] = 0x00;
        endlessVarint[1] = 0x3C;

        assertEquals(IworkDocumentHeader.UNRECOGNIZED, IworkDocumentHeader.firstMessageType(pages, 8));
        assertEquals(IworkDocumentHeader.UNRECOGNIZED, IworkDocumentHeader.firstMessageType(new byte[] {0x01, 0, 0, 0, 0}, 5));
        assertEquals(IworkDocumentHeader.UNRECOGNIZED, IworkDocumentHeader.firstMessageType(copyBeforeAnything, copyBeforeAnything.length));
        assertEquals(IworkDocumentHeader.UNRECOGNIZED, IworkDocumentHeader.firstMessageType(endlessVarint, endlessVarint.length));
    }
}
