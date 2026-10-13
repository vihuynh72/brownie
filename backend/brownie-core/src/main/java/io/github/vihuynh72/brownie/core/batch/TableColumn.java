package io.github.vihuynh72.brownie.core.batch;

/**
 * One column of a read table: its 0-based position, the letter a person
 * knows it by in a spreadsheet ({@code A}, {@code B}, ... {@code AA}), the
 * name it was given (the header's text, or {@code "Column C"} when there is
 * none), what its cells agree on, and how many data rows leave it blank.
 */
public record TableColumn(int index, String letter, String name, ColumnType inferredType, int blankCount) {

    public TableColumn {
        if (index < 0) {
            throw new IllegalArgumentException("A column index is never negative.");
        }
    }
}
