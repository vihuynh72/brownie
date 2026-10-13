package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * One row of a read table. {@code rowNumber} is the number the person sees:
 * for a CSV, the record's own number counting the header record as row 1
 * (a quoted record spread over lines is still one row); for a workbook, the
 * sheet's own row index. {@code blank} means every cell displays nothing.
 */
public record TableRow(int rowNumber, List<TableCell> cells, boolean blank) {

    public TableRow {
        cells = List.copyOf(cells);
        if (rowNumber <= 0) {
            throw new IllegalArgumentException("A row number is never below one.");
        }
    }
}
