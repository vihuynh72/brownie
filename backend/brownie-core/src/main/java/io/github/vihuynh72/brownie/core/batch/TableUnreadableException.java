package io.github.vihuynh72.brownie.core.batch;

import java.util.Objects;

/**
 * A file could not be read as a table of rows. The {@link Reason} is what a
 * person is told; {@code rowNumber} names the row a person sees that the
 * reading reached, when one did.
 */
public class TableUnreadableException extends RuntimeException {

    /** Why the file could not be read as a table, in terms a person can act on. */
    public enum Reason {
        /** The content's shape is not a table of rows -- a stray quote, an uneven record, no table at all. */
        NOT_A_TABLE,
        /** The file holds no data rows to fill from. */
        EMPTY,
        TOO_MANY_ROWS,
        TOO_MANY_COLUMNS,
        CELL_TOO_LONG,
        /** The named sheet is not in the workbook. */
        SHEET_NOT_FOUND,
        /** The text is not well-formed UTF-8, so the file cannot be trusted to be read as written. */
        NOT_UTF8,
        /** The file's bytes are a document, not something a table can be read from. */
        NOT_A_SPREADSHEET,
        /** Recognizable, but malformed or built in a way no real workbook is. */
        DAMAGED
    }

    private final Reason reason;
    private final Integer rowNumber;

    public TableUnreadableException(Reason reason, String message) {
        this(reason, message, null);
    }

    public TableUnreadableException(Reason reason, String message, Integer rowNumber) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.rowNumber = rowNumber;
    }

    public Reason reason() {
        return reason;
    }

    public Integer rowNumber() {
        return rowNumber;
    }
}
