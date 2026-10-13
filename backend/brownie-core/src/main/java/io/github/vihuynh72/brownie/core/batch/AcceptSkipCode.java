package io.github.vihuynh72.brownie.core.batch;

/**
 * Why an accept step left a row's review untouched. NOT_CREATED covers a
 * row that never filled cleanly (FAILED, SKIPPED_BLANK or still PENDING);
 * the others describe a row whose document exists but is not yet in the
 * exact shape an unreviewed batch accept requires.
 */
public enum AcceptSkipCode {
    NOT_CREATED,
    DOCUMENT_GONE,
    ALREADY_ACCEPTED,
    SUGGESTION_WAITING,
    AI_COMPOSED,
    BLOCKING,
    HAS_PROBLEMS
}
