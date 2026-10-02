package io.github.vihuynh72.brownie.core.document;

/**
 * One thing filling or checking a PDF found. {@code fieldId} is the fill
 * item's id, or null for a finding about the document as a whole. {@code
 * detail} carries the one fact the code names (see {@link
 * PdfFillFindingCode}), or null.
 */
public record PdfFillFinding(String fieldId, PdfFillFindingCode code, String detail) {

    public boolean blocking() {
        return code.blocking();
    }
}
