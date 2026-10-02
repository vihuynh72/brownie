package io.github.vihuynh72.brownie.core.prepare;

/**
 * A spot cannot be made where it was asked for. {@link Reason} says why, so
 * the person can be told in words and choose another place.
 */
public class FillSpotPlacementException extends RuntimeException {

    public enum Reason {
        /** The place is inside a link. */
        INSIDE_LINK,
        /** The place is inside a field Word works out, such as a page number. */
        INSIDE_FIELD_CODE,
        /** The place is in a header or footer, which the filler does not reach. */
        HEADER_FOOTER,
        /** The place is in the row or paragraph that repeats for each item. */
        REPEATING_REGION,
        /** The place is inside a region the template protects. */
        PROTECTED,
        /** The paragraph is not where it was, or its text changed, since the place was chosen. */
        ANCHOR_STALE,
        /** No paragraph, control or spot matches what the edit names. */
        NOT_FOUND,
        /** A box on a PDF page is not wholly on its page, or names a page the PDF does not have. */
        OFF_PAGE,
        /** A box on a PDF page is too small to write in. */
        TOO_SMALL,
        /** A box on a PDF page would cover another spot or one of the PDF's own form fields. */
        OVERLAPS,
        /** A box is on a PDF page Brownie cannot write on. */
        NOT_FILLABLE
    }

    private final Reason reason;

    public FillSpotPlacementException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
