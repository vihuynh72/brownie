package io.github.vihuynh72.brownie.core.batch;

/**
 * Why a cell could not offer a value to read. The cell's display is left
 * blank in both cases: an unreadable value is never shown as if it were
 * data.
 */
public enum CellProblem {
    /** A formula cell whose cached result is absent, so there is no value to read without evaluating it. */
    FORMULA_WITHOUT_RESULT,
    /** A formula whose last evaluation produced an error rather than a value. */
    ERROR_VALUE
}
