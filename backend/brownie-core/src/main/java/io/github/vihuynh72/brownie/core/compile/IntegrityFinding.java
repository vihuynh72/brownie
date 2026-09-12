package io.github.vihuynh72.brownie.core.compile;

/**
 * Whether one field's intended text actually survived into the reopened
 * DOCX body and the rendered PDF's own extracted text. A check that finds
 * nothing to look for (a field the user left blank under policy) is never
 * reported here at all -- there is no missing text to fail against.
 */
public record IntegrityFinding(String fieldId, String expectedText, boolean foundInDocx, boolean foundInPdf) {

    public boolean passed() {
        return foundInDocx && foundInPdf;
    }
}
