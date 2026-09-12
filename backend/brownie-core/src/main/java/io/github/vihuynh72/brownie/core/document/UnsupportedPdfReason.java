package io.github.vihuynh72.brownie.core.document;

/** A whole-document reason a PDF falls outside the qualified subset -- unlike DOCX's per-location findings, both reasons here are properties of the entire file. */
public enum UnsupportedPdfReason {
    /** The package requires a password this extractor was never given one to try. Rejected, not silently stripped. */
    ENCRYPTED,
    /** Every page produced zero extractable text -- most likely a fully scanned or image-only document, which needs OCR support this product does not have yet. */
    NO_EXTRACTABLE_TEXT
}
