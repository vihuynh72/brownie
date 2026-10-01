package io.github.vihuynh72.brownie.core.document;

/**
 * Writes values into a PDF. A fillable form stays fillable: each named
 * field gets its value and a drawn appearance, and nothing is flattened.
 * Text for a box is drawn on the page itself. A value that cannot be
 * written as asked (a character the font lacks, a script that needs
 * shaping, text too long for its place) is never forced in: it is left
 * out and reported as a finding, and filling carries on with the rest.
 */
public interface PdfFormFiller {

    /** Names the filler, the library and the fonts, for the record of how an output was made. */
    String fillerVersion();

    /**
     * @throws PdfFormNotFillableException when the PDF is locked, signed, an
     *         XFA form or cannot be read at all
     */
    FilledPdf fill(byte[] sourcePdf, PdfFillRequest request);
}
