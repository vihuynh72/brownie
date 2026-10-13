package io.github.vihuynh72.brownie.core.batch;

/** A mapping or the title names a column the read table does not have. */
public class BatchUnknownColumnException extends RuntimeException {

    private final int column;

    public BatchUnknownColumnException(int column) {
        super("Column " + TableRules.columnLetter(column) + " is not a column of the table.");
        this.column = column;
    }

    public int column() {
        return column;
    }
}
