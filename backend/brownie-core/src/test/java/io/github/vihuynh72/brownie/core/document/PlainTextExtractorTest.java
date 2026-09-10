package io.github.vihuynh72.brownie.core.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlainTextExtractorTest {

    private final PlainTextExtractor extractor = new PlainTextExtractor();

    @Test
    void parserVersionIsStableAndNonBlank() {
        assertEquals(PlainTextExtractor.PARSER_VERSION, extractor.parserVersion());
        assertFalse(extractor.parserVersion().isBlank());
    }

    @Test
    void ordinaryUtf8TextRoundTripsWithNormalizedLineEndings() throws IOException {
        String text = "Meeting called to order.\r\nAttendees: Jordan Lee.\r\n";
        PlainTextStructuralGraph graph = extract(text.getBytes(StandardCharsets.UTF_8));

        assertEquals(text, graph.originalText());
        assertEquals("Meeting called to order.\nAttendees: Jordan Lee.\n", graph.normalizedText());
    }

    @Test
    void accentedUnicodeTextSurvivesIntact() throws IOException {
        String text = "Attendee: José Núñez";
        PlainTextStructuralGraph graph = extract(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(text, graph.originalText());
        assertEquals(text, graph.normalizedText());
    }

    @Test
    void aLeadingUtf8ByteOrderMarkIsStrippedRatherThanAppearingAsText() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] withBom = concat(bom, "Meeting notes".getBytes(StandardCharsets.UTF_8));

        PlainTextStructuralGraph graph = extract(withBom);
        assertEquals("Meeting notes", graph.originalText());
        assertFalse(graph.originalText().startsWith("﻿"));
    }

    @Test
    void anEmptyFileExtractsToEmptyText() throws IOException {
        PlainTextStructuralGraph graph = extract(new byte[0]);
        assertEquals("", graph.originalText());
        assertEquals("", graph.normalizedText());
    }

    @Test
    void malformedUtf8BytesThrowPlainTextParseExceptionRatherThanSilentlySubstitutingReplacementCharacters() {
        // 0xFF is not a valid start byte in any well-formed UTF-8 sequence.
        byte[] invalid = {'a', (byte) 0xFF, 'b'};
        assertThrows(PlainTextParseException.class, () -> extractor.extract(new ByteArrayInputStream(invalid)));
    }

    @Test
    void aLoneTrailingCrIsNormalizedTheSameAsElsewhere() throws IOException {
        PlainTextStructuralGraph graph = extract("last line\r".getBytes(StandardCharsets.UTF_8));
        assertEquals("last line\n", graph.normalizedText());
    }

    private PlainTextStructuralGraph extract(byte[] bytes) throws IOException {
        return extractor.extract(new ByteArrayInputStream(bytes));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
