package io.github.vihuynh72.brownie.core.batch;

/** What a column's non-blank cells agree on, for suggesting which kind of field it can fill. */
public enum ColumnType {
    TEXT,
    DATE,
    NUMBER,
    /** No non-blank cell at all. */
    EMPTY
}
