package io.github.vihuynh72.brownie.core.evidence;

/**
 * A locator that does not actually resolve against its source's real
 * extraction graph: a DOCX part or node that does not exist, a PDF page or
 * line out of range, or a code-point range invalid for the addressed
 * text. Rejected outright at span-creation time -- a span is never
 * created pointing at something that cannot really be shown.
 */
public class InvalidEvidenceLocatorException extends RuntimeException {

    public InvalidEvidenceLocatorException(String message) {
        super(message);
    }
}
