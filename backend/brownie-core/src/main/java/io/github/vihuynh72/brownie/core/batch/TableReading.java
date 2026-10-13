package io.github.vihuynh72.brownie.core.batch;

import java.util.List;

/**
 * What reading one file as a table produced: which sheets it offered, which
 * was read, whether the first row was taken as names, whether any numeric
 * date could be read two ways, the columns it was found to have, the data
 * rows, and anything worth telling the person. {@code readerVersion} names
 * the exact reader that produced this, so a batch can refuse to re-read the
 * file with a different one mid-fill.
 */
public record TableReading(
        TableFormat format,
        String readerVersion,
        List<String> sheetNames,
        String sheetName,
        boolean hasHeaderRow,
        boolean ambiguousDates,
        List<TableColumn> columns,
        List<TableRow> rows,
        List<TableNotice> notices) {

    public TableReading {
        sheetNames = List.copyOf(sheetNames);
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        notices = List.copyOf(notices);
    }

    /** The data rows with anything in them -- the ones a batch can fill from. */
    public long nonBlankRowCount() {
        return rows.stream().filter(row -> !row.blank()).count();
    }
}
