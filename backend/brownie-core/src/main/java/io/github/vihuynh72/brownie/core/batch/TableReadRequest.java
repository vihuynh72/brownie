package io.github.vihuynh72.brownie.core.batch;

/**
 * How to read one file as a table. {@code sheetName} names the sheet for a
 * workbook, null taking the first visible one and meaningless for a CSV.
 * {@code hasHeaderRow} is the person's say-so when they have given it, null
 * asking the reader to work it out. {@code dateOrder} settles two-way
 * numeric dates, null reading month-first.
 */
public record TableReadRequest(String sheetName, Boolean hasHeaderRow, DateOrder dateOrder) {

    /** The order numeric dates are read in when none was chosen: month first. */
    public DateOrder effectiveDateOrder() {
        return dateOrder == null ? DateOrder.MDY : dateOrder;
    }
}
