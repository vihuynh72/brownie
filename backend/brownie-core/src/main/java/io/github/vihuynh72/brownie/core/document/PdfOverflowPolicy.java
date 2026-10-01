package io.github.vihuynh72.brownie.core.document;

/**
 * What happens when text does not fit where it goes. {@link #SHRINK_TO_FIT}
 * makes the text smaller, half a point at a time, down to six points, and
 * says so; still too long at six points, it is an overflow like {@link
 * #BLOCK}, which reports the overflow at once. Text is never cut short.
 */
public enum PdfOverflowPolicy {
    SHRINK_TO_FIT,
    BLOCK
}
