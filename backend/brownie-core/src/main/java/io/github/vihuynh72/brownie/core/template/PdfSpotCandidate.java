package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;

import java.util.List;

/**
 * A place on a PDF page that looks like a blank to write in, as {@link
 * PdfSpotCandidateDetector} found it. It is a guess from the page's shapes
 * and words, never a claim about what belongs there. {@code id} ("c1",
 * "c2", ...) is unique within one detection and is what a naming step
 * refers to. {@code contextText} is the words around the place (at most 80
 * characters), or for a box in a table its column and row ("column: Year;
 * row: Painting"), and {@code labelGuess} the name the words around it
 * suggest, or null. {@code style} is how text written there should look,
 * taken from the words beside it. {@code signatureLike} marks a line for a
 * signature or initials: it is reported, not dropped, and the caller leaves
 * it for the person to sign.
 *
 * <p>{@code gridKey} names the grid of drawn boxes (a table) a box is a
 * cell of ({@code P1G1}), or is null; the boxes of one grid are filled in
 * whole or not at all. {@code tableValues} is the text printed in that
 * grid's cells under its header, which never names one of its boxes.
 * {@code forOfficeUse} marks a place under words that keep it for the
 * office ("For office use only"), which a person may not be meant to fill.
 */
public record PdfSpotCandidate(
        String id,
        int pageNumber,
        PdfRect box,
        Kind kind,
        String contextText,
        String labelGuess,
        PdfTextStyle style,
        boolean signatureLike,
        String gridKey,
        List<String> tableValues,
        boolean forOfficeUse) {

    public PdfSpotCandidate {
        tableValues = tableValues == null ? List.of() : List.copyOf(tableValues);
    }

    /** What on the page the place was found from. */
    public enum Kind {
        /** A run of underscores. */
        UNDERSCORES,
        /** A run of dots. */
        DOT_LEADER,
        /** Empty space after a label that ends with a colon. */
        LABEL_SPACE,
        /** A drawn line with nothing written above it. */
        RULE,
        /** A drawn rectangle or table cell with nothing in it. */
        EMPTY_BOX
    }
}
