package io.github.vihuynh72.brownie.core.batch;

/**
 * Why a row did not fill cleanly. A code is all a row ever records of a
 * cell's trouble -- never the cell's text, which is the person's data and
 * does not belong in a problem list.
 */
public enum BatchRowProblemCode {
    /** The field wanted a date and the cell did not hold one. */
    NOT_A_DATE,
    /** The cell is a formula whose result was never stored. */
    FORMULA_WITHOUT_RESULT,
    /** The cell's last evaluation produced an error. */
    ERROR_VALUE,
    /** The document the row would create did not pass content validation. */
    CONTENT_REFUSED,
    /** The compose reply could not be read as the strict reply shape. */
    RESPONSE_UNREADABLE,
    /** The compose reply arrived but held no usable text for any place. */
    NOTHING_COMPOSED,
    /** The model could not be reached for the compose call. */
    MODEL_UNAVAILABLE,
    /** The row's document was gone before the step reached it. */
    DOCUMENT_GONE
}
