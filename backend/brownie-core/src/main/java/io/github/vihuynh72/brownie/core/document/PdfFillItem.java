package io.github.vihuynh72.brownie.core.document;

/**
 * One value to write into a PDF. {@code value} is the text exactly as it
 * should read, already formatted by the caller (a date arrives as the words
 * to print, never as a date to format); an empty value clears a field and
 * draws nothing in a box. {@code fieldId} is the caller's own name for it,
 * used only to say which value a finding is about.
 */
public record PdfFillItem(String fieldId, PdfFillTarget target, String value, PdfOverflowPolicy overflow) {

    public PdfFillItem {
        if (fieldId == null || fieldId.isEmpty() || target == null || overflow == null) {
            throw new IllegalArgumentException("A fill item needs a field id, a target and an overflow policy.");
        }
        value = value == null ? "" : value;
    }
}
