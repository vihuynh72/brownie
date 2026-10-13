package io.github.vihuynh72.brownie.core.batch;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an OOXML workbook as a table. The implementation lives with the
 * other Apache POI code in the API module; this port is what {@link
 * BatchService} and the table route ask through, so core never names a
 * reading library.
 */
public interface XlsxTableReader {

    /** The exact reader's version, stored on a batch so a later change in how workbooks are read cannot silently re-read a batch's file. */
    String readerVersion();

    /**
     * Reads the workbook into a {@link TableReading} under {@code limits},
     * throwing {@link TableUnreadableException} when the bytes cannot be
     * read as one. Reads at most as far as the limits make necessary.
     */
    TableReading read(InputStream content, TableReadRequest request, TableLimits limits) throws IOException;
}
