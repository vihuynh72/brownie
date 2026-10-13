package io.github.vihuynh72.brownie.api.document.table;

import io.github.vihuynh72.brownie.core.batch.DateOrder;
import io.github.vihuynh72.brownie.core.batch.TableReadRequest;

/** The body of a table read: which sheet, whether the first row names the columns, and which way numeric dates run. */
record ReadTableRequest(String sheet, Boolean hasHeaderRow, DateOrder dateOrder) {

    TableReadRequest toDomain() {
        return new TableReadRequest(sheet, hasHeaderRow, dateOrder);
    }
}
