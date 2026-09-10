package io.github.vihuynh72.brownie.core.document;

import io.github.vihuynh72.brownie.core.text.NormalizedText;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes a plain-text artifact's bytes and normalizes its line endings.
 * A plain, concrete class rather than an interface behind an infrastructure
 * adapter the way {@code DocxStructuralExtractor}/{@code
 * PdfStructuralExtractor} are: those exist to keep Apache POI/PDFBox --
 * real external libraries -- out of {@code brownie-core}. Decoding UTF-8
 * and normalizing line endings uses only the JDK and this module's own
 * {@link NormalizedText}, so there is no infrastructure boundary to push
 * an implementation across; the same reasoning already lets {@code
 * DocumentExtractionService} depend on {@code ArtifactService} directly
 * rather than through an interface.
 */
public class PlainTextExtractor {

    /** Identifies this extractor's own graph shape. No external library version to track alongside it, unlike the DOCX/PDF extractors. */
    static final String PARSER_VERSION = "brownie-plain-text-graph-v1";

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    public String parserVersion() {
        return PARSER_VERSION;
    }

    /**
     * Strictly UTF-8 -- the one encoding this pilot supports. Malformed or
     * unmappable byte sequences are reported as a {@link
     * PlainTextParseException}, never silently replaced with U+FFFD: a
     * "successful" decode that quietly substitutes corrupted characters
     * would be worse than an honest failure. A leading UTF-8 byte-order
     * mark, which several ordinary text editors add, is stripped before
     * decoding so it never appears as the text's own first character.
     */
    public PlainTextStructuralGraph extract(InputStream content) throws IOException {
        byte[] bytes = content.readAllBytes();
        int offset = startsWithBom(bytes) ? UTF8_BOM.length : 0;

        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String original;
        try {
            original = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new PlainTextParseException("The artifact's bytes are not well-formed UTF-8 text.", e);
        }

        NormalizedText normalized = NormalizedText.normalizeLineEndings(original);
        return new PlainTextStructuralGraph(PARSER_VERSION, normalized.original(), normalized.normalized());
    }

    private static boolean startsWithBom(byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }
}
