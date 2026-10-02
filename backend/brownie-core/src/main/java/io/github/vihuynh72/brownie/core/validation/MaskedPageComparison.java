package io.github.vihuynh72.brownie.core.validation;

/**
 * One page of a masked comparison: how many pixels were compared, and how
 * many of them differ outside every mask. {@code pictureNotCompared} is
 * true when the page draws a picture in a format that cannot be decoded
 * here (JBIG2 and JPEG 2000, common in scans), which is then left out of
 * both drawings: the rest of the page, text and lines included, is still
 * compared.
 */
public record MaskedPageComparison(
        int pageNumber, Outcome outcome, long pixelsCompared, long pixelsChangedOutsideMasks, boolean pictureNotCompared) {

    /**
     * The share of a page's pixels that may differ outside the masks before
     * the page counts as changed, and the fewest pixels that ever count:
     * drawing the same page twice gives the same pixels, so anything beyond
     * a speck of anti-aliasing at a mask's edge is a real change.
     */
    public static final double CHANGED_SHARE = 0.0002;
    public static final long MIN_CHANGED_PIXELS = 5;

    public enum Outcome {
        COMPARED,
        /** The page is too large to draw at the comparison's resolution. */
        NOT_DRAWN_TOO_LARGE,
        /** Drawing the page ran past the time allowed. */
        NOT_DRAWN_TOO_SLOW,
        /** The page could not be drawn at all. */
        NOT_DRAWN_FAILED
    }

    public boolean changed() {
        return outcome == Outcome.COMPARED && pixelsChangedOutsideMasks >= threshold(pixelsCompared);
    }

    public static long threshold(long pixelsCompared) {
        return Math.max(MIN_CHANGED_PIXELS, (long) Math.ceil(CHANGED_SHARE * pixelsCompared));
    }
}
