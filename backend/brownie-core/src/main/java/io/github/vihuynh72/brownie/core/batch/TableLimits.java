package io.github.vihuynh72.brownie.core.batch;

/**
 * The hard bounds any reading of a table works inside. {@code maxDataRows}
 * is deployment configuration ({@code brownie.batch.max-rows}); the rest are
 * fixed because a reasonable spreadsheet never comes near them and past them
 * the file is not a table of rows a person is filling from.
 */
public record TableLimits(int maxDataRows, int maxColumns, int maxCellChars, int maxHeaderChars) {

    public static final int MAX_COLUMNS = 100;
    public static final int MAX_CELL_CHARS = 10_000;
    public static final int MAX_HEADER_CHARS = 200;

    /** The bounds with only the row limit left to configuration. */
    public static TableLimits ofRows(int maxDataRows) {
        return new TableLimits(maxDataRows, MAX_COLUMNS, MAX_CELL_CHARS, MAX_HEADER_CHARS);
    }
}
