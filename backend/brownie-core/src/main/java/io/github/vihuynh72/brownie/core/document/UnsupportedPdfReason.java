package io.github.vihuynh72.brownie.core.document;

/** A whole-document reason a PDF falls outside the qualified subset -- unlike DOCX's per-location findings, every reason here is a property of the entire file. */
public enum UnsupportedPdfReason {
    /** The package requires a password this extractor was never given one to try. Rejected, not silently stripped. */
    ENCRYPTED,
    /** Every page produced zero extractable text -- most likely a fully scanned or image-only document, which needs OCR support this product does not have yet. */
    NO_EXTRACTABLE_TEXT,
    /** More pages than this product reads. The whole file is refused; reading the first so many and saying nothing about the rest would present part of a document as all of it. */
    TOO_MANY_PAGES,
    /** A small file whose compressed contents expand, or whose text runs, far beyond what any document of its size holds: the shape of a file built to exhaust whoever opens it. */
    TOO_LARGE_WHEN_EXPANDED
}
